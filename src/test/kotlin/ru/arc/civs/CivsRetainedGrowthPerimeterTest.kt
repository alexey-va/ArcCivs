package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

/** Constructed public planning geometry, not a captured city or proof of native construction. */
class CivsRetainedGrowthPerimeterTest : FunSpec({
    test("control grows beyond a filled paid inner perimeter when the next default axes are clear") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = false)
        fixture.assertCurrentQuarterFull()
        val original = CivsSettlementPlans.prepare(fixture.city, fixture.templates, fixture.surface, fixture.access,
            profile = CivsSettlementGrowth.profile(fixture.city), halfWidth = 32)
        val grown = try {
            CivsSettlementPlans.prepareGrowth(fixture.city, fixture.templates, fixture.surface,
                fixture.access, CivsSettlementGrowth.profile(fixture.city))
        } catch (failure: CivsRejection) {
            throw AssertionError("SETUP: non-conflicting control rejected: ${failure.reason} ${failure.values}", failure)
        }
        fixture.assertCompleteGrowth(grown, "SETUP: non-conflicting control")
        grown shouldBe original
    }

    test("growth can avoid an active farm on the default next axis without moving paid inner work") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true)
        fixture.assertCurrentQuarterFull()
        val before = Gson().toJson(listOf(fixture.city, fixture.access, fixture.journals, fixture.surface))
        val default = CivsSettlementLayout.frame(fixture.city, 32)
        val farm = fixture.city.buildings.single { it.type == BuildingType.FARM }
        default.gates.single { it.side == 2 }.center.let {
            CivsAutonomousGeometry.horizontalContains(farm.bounds, it) shouldBe true
        }

        // One concrete alternative, not merely an offset gate: the complete south edge goes
        // six columns beyond the next square canvas and encloses the existing open farm.
        // This remains inside the same city, supported contour span and allowed next width.
        val alternate = default.copy(gates = listOf(retainedGrowthGate(1, 32, 7)),
            wallObstacles = retainedGrowthRing(-32, -32, 32, 38, gateZ = 7))
        val intent = fixture.plan.copy(halfWidth = 32, walls = alternate.wallObstacles,
            gates = alternate.gates.map { it.passage }, gateSides = alternate.gates.map { it.side })
        val proposed = fixture.city.copy(autonomy = fixture.city.autonomy!!.copy(plan = intent, layoutHalfWidth = 32))
        CivsSettlementPlans.validate(proposed, intent)
        proposed.buildings shouldBe fixture.city.buildings
        CivAutonomousAccess.from(proposed, fixture.journals) shouldBe fixture.access
        (intent.halfWidth in CivsSettlementGrowth.halfWidths(fixture.city)) shouldBe true
        val witness = try {
            // Full production HOUSE selection, grade/route search and infrastructure admission.
            // Only prospective spatial intent differs; templates, terrain and paid authority do not.
            CivsSettlementPlans.prepare(proposed, fixture.templates, fixture.surface, fixture.access,
                profile = CivsSettlementGrowth.profile(fixture.city), halfWidth = 32)
        } catch (failure: CivsRejection) {
            throw AssertionError("SETUP: alternate complete-contour witness rejected: ${failure.reason} ${failure.values}", failure)
        }
        val infrastructure = fixture.assertCompleteGrowth(witness, "SETUP: alternate complete-contour witness")
        witness.walls shouldBe alternate.wallObstacles
        witness.gates shouldBe alternate.gates.map { it.passage }
        val gate = infrastructure.pieces.single { it.type == BuildingType.GATE && it.id !in fixture.access.infrastructure.map { old -> old.id } }
        val corridor = (30..34).flatMap { x -> (6..8).map { z -> CivPosition(x, 65, z) } }
        corridor.all { it in gate.transit } shouldBe true
        val straightApproach = (26..38).flatMap { x -> (6..8).map { z -> CivPosition(x, 65, z) } }
        val ordinary = witness.plots.map { it.envelope }
        straightApproach.none { p -> ordinary.any { CivsAutonomousGeometry.horizontalContains(it, p) } } shouldBe true
        val body = straightApproach.flatMapTo(hashSetOf()) { listOf(it, it.copy(y = it.y + 1)) }
        infrastructure.pieces.filter { it.type in setOf(BuildingType.WALL, BuildingType.GATE) }
            .flatMap { it.blocks }.none { it.position in body && it.after != "minecraft:air" } shouldBe true
        Gson().toJson(listOf(fixture.city, fixture.access, fixture.journals, fixture.surface)) shouldBe before

        // Only a failure here is the intended missing-choice RED. Any witness/control failure
        // above is a fixture failure and does not establish a planner defect or a live cause.
        val grown = try {
            CivsSettlementPlans.prepareGrowth(fixture.city, fixture.templates, fixture.surface,
                fixture.access, CivsSettlementGrowth.profile(fixture.city))
        } catch (failure: CivsRejection) {
            throw AssertionError("PLANNER CHALLENGE: a complete paid-preserving width-32 witness exists; " +
                "prepareGrowth rejected ${failure.reason} ${failure.values}", failure)
        }
        fixture.assertCompleteGrowth(grown, "planner result")
        Gson().toJson(listOf(fixture.city, fixture.access, fixture.journals, fixture.surface)) shouldBe before
    }

    test("an accepted current quarter stays exact when the requested profile already fits") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true)
        val profile = fixture.plan.plots.map { it.building.type }
        val original = CivsSettlementPlans.prepare(fixture.city, fixture.templates, fixture.surface, fixture.access,
            profile = profile, halfWidth = 24)
        val grown = CivsSettlementPlans.prepareGrowth(fixture.city, fixture.templates, fixture.surface, fixture.access, profile)
        grown shouldBe original
        grown.halfWidth shouldBe 24
        grown.walls shouldBe fixture.plan.walls
        grown.gates shouldBe fixture.plan.gates
    }

    test("a retained farm requiring more than eight extra columns does not enlarge the territory") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true, farmDepth = 9)
        fixture.assertCurrentQuarterFull()
        fixture.refusesGrowth()
        fixture.city.radius shouldBe 56
        fixture.city.autonomy!!.layoutHalfWidth shouldBe 24
    }

    for (unloaded in listOf(true, false))
    test("the required outer wall refuses ${if (unloaded) "unloaded" else "unowned non-natural"} terrain") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true)
        fixture.copy(surface = fixture.surface.copy(columns = fixture.surface.columns.toMutableMap().apply {
            for (x in -33..33) put(civsTerrainKey(x, 38),
                CivTerrainSurfaceColumn(64, natural = unloaded, loaded = !unloaded))
        })).refusesGrowth()
    }

    test("an unavailable straight apron at both remaining exits cannot leave a gate hole") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true)
        fixture.copy(surface = fixture.surface.copy(columns = fixture.surface.columns.toMutableMap().apply {
            for (x in listOf(-36, 36)) put(civsTerrainKey(x, 7), CivTerrainSurfaceColumn(64, true, loaded = false))
        })).refusesGrowth()
    }

    test("a paid wall on the candidate edge is not absorbed or overwritten") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true)
        val frame = CivsSettlementLayout.frame(fixture.city).copy(gates = emptyList(), wallObstacles = listOf(
            CivBox(CivPosition(32, 64, 20), CivPosition(32, 64, 22))))
        val piece = CivsSettlementLayout.infrastructure(fixture.city, frame, fixture.surface, fixture.access.routes.values.toList(),
            fixture.access.infrastructure, pavingRoutes = emptyList(), retainedRoutes = fixture.access.routes.values)
            .also { it.rejected shouldBe emptyMap() }.pieces.single { next -> fixture.access.infrastructure.none { it.id == next.id } }
        val building = CivsInfrastructure.building(piece).copy(phase = BuildingPhase.ACTIVE)
        val city = fixture.city.copy(buildings = fixture.city.buildings + building)
        val journal = CivPlacementPlan(CivsGateRefit.retainedPlanId(building), city.id, city.worldId, building,
            piece.blocks.map { CivPlanBlock(it.position, if (it.position.y == 64) "minecraft:grass_block" else "minecraft:air", it.after) })
        val journals = fixture.journals + journal
        val access = CivAutonomousAccess.from(city, journals)
        access.infrastructure.single { it.id == piece.id } shouldBe piece
        CivsRules.validate(city) shouldBe city
        fixture.copy(city = city, journals = journals, access = access,
            surface = retainedGrowthSurface(city, access)).refusesGrowth()
    }

    test("a paid infrastructure approach cannot be ignored to squeeze two crossings through one gate") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true)
        val owner = fixture.city.buildings.single { it.type == BuildingType.WALL && it.bounds.min.z == -10 && it.bounds.max.z == -10 }
        owner.entrance shouldBe CivPosition(0, 65, -9)
        val route = retainedGrowthRoute(0 to -1, 0 to 0, 26 to 0, 26 to 45, 24 to 45, 24 to 2,
            12 to 2, 12 to 1, 0 to 1, -4 to 1, -4 to -9, 0 to -9)
        route.last() shouldBe owner.entrance
        val journals = fixture.journals.map { journal -> if (journal.building.id != owner.id) journal else journal.copy(
            terrain = CivTerraformPlan(CivBox(CivPosition(-56, 64, -56), CivPosition(56, 66, 56)), emptyList(), route, 64, 0, 0)) }
        val access = CivAutonomousAccess.from(fixture.city, journals)
        access.routes.getValue(owner.id) shouldBe route
        access.routes.size shouldBe fixture.access.routes.size + 1
        access.infrastructure shouldBe fixture.access.infrastructure
        fixture.copy(journals = journals, access = access,
            surface = retainedGrowthSurface(fixture.city, access)).refusesGrowth()
    }

    test("growth retains an existing paid rising and falling route without flattening its grades") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true, gradedFarm = true)
        fixture.assertCurrentQuarterFull()
        val farm = fixture.city.buildings.single { it.type == BuildingType.FARM }
        val paid = fixture.access.routes.getValue(farm.id)
        paid.map { it.y }.toSet() shouldBe setOf(65, 66)
        val grown = CivsSettlementPlans.prepareGrowth(fixture.city, fixture.templates, fixture.surface, fixture.access,
            CivsSettlementGrowth.profile(fixture.city))
        grown.routes.getValue(farm.id.toString()) shouldBe paid
        fixture.assertCompleteGrowth(grown, "graded paid route")
        CivAutonomousAccess.from(fixture.city, fixture.journals).routes.getValue(farm.id) shouldBe paid
    }

    test("a legacy perimeter gate connection reports real route budget exhaustion") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = false)
        val gate = CivsSettlementLayout.frame(fixture.city, 32).gates.first()
        gate.center shouldBe CivPosition(32, 64, 7)
        // The isolated tall target is horizontally connected but cannot be reached through
        // the flat columns' unchanged +/-24 grade domain. Exercise the real bounded search.
        val surface = fixture.surface.copy(columns = fixture.surface.columns +
            (civsTerrainKey(gate.center.x, gate.center.z) to CivTerrainSurfaceColumn(200, true)))
        val searches = mutableListOf<CivRouteSearchStats>()
        val failure = shouldThrow<CivsRejection> {
            CivsSettlementPlans.prepare(fixture.city, fixture.templates, surface, fixture.access,
                profile = fixture.plan.plots.map { it.building.type }, halfWidth = 32, inspectRoute = searches::add)
        }
        failure.reason shouldBe "autonomy-no-path"
        withClue("SETUP: must exercise the actual route budget, not an earlier geometric rejection: $searches") {
            searches.any { it.outcome == "route-budget" &&
                it.expanded == CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES && it.to == gate.center.copy(y = 201) } shouldBe true
        }
    }

    test("a retained gate cannot make an AIR filled outer wall footprint count as closure") {
        val fixture = retainedGrowthFixture(farmOnNextAxis = true, southInnerGate = true)
        fixture.assertCurrentQuarterFull()
        val nominal = CivsSettlementLayout.frame(fixture.city, 32)
        val candidate = CivsRetainedGrowthPerimeter.candidates(fixture.city, nominal, fixture.surface,
            fixture.access, fixture.plan.plots.associate { it.building.id to it.envelope }).single()
        candidate.gates.single().center shouldBe CivPosition(-32, 64, 7)
        val east = candidate.wallObstacles.single { it.min.x == 31 && it.max.x == 33 }
        east.min.z shouldBe -30
        east.max.z shouldBe 36
        // Real producer-path witness: the nearby retained SOUTH gate makes the unchanged
        // wall producer choose the transverse axis for this long EAST strip.
        CivsInfrastructureRebuild.wallAxis(fixture.city, east) shouldBe true
        val built = CivsSettlementLayout.infrastructure(fixture.city, candidate, fixture.surface,
            fixture.access.routes.values.toList(), fixture.access.infrastructure, pavingRoutes = emptyList(),
            retainedRoutes = fixture.access.routes.values)
        built.perimeterClosed shouldBe true
        built.rejected shouldBe emptyMap()
        val paid = fixture.access.infrastructure.mapTo(hashSetOf()) { it.id }
        fixture.access.infrastructure.forEach { old -> built.pieces.single { it.id == old.id } shouldBe old }
        val blocked = built.pieces.filter { it.id !in paid && it.type in setOf(BuildingType.WALL, BuildingType.GATE) }
            .flatMap { it.blocks }.filter { it.position.y in 65..66 && it.after != "minecraft:air" }
            .mapTo(hashSetOf()) { it.position.x to it.position.z }
        // Independent flood starts beyond the old south wall, closes only the declared new
        // exit, and still escapes through the incorrectly produced east side. Floors cannot block it.
        for (gate in candidate.gates) for (z in gate.passage.min.z + 1 until gate.passage.max.z)
            blocked += gate.center.x to z
        retainedGrowthEscapes(0 to 20, blocked, fixture.city.radius) shouldBe true
        val failure = shouldThrow<CivsRejection> {
            CivsRetainedGrowthPerimeter.validateResult(candidate, fixture.access, built, fixture.access.routes.values)
        }
        failure.reason shouldBe "autonomy-layout-obstructed"
        failure.values["stage"] shouldBe "retained-growth-result"
        fixture.refusesGrowth()
    }
})

private data class RetainedGrowthFixture(
    val city: Settlement,
    val plan: CivSettlementPlan,
    val templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>,
    val surface: CivTerrainSurface,
    val journals: List<CivPlacementPlan>,
    val access: CivAutonomousAccess,
) {
    fun refusesGrowth(): CivsRejection {
        val before = Gson().toJson(listOf(city, access, journals, surface))
        val failure = shouldThrow<CivsRejection> {
            CivsSettlementPlans.prepareGrowth(city, templates, surface, access, CivsSettlementGrowth.profile(city))
        }
        (failure.reason in setOf("autonomy-no-site", "autonomy-no-path", "autonomy-layout-obstructed")) shouldBe true
        Gson().toJson(listOf(city, access, journals, surface)) shouldBe before
        return failure
    }

    fun assertCurrentQuarterFull() {
        CivsRules.validate(city) shouldBe city
        CivsSettlementPlans.current(city) shouldBe plan
        CivsSettlementGrowth.halfWidths(city) shouldBe listOf(24, 32)
        CivsAutonomy.nextGoal(city)?.type shouldBe BuildingType.HOUSE
        val house = templates.getValue(BuildingType.HOUSE)
        val frame = CivsSettlementLayout.frame(city)
        withClue("SETUP: the accepted compact interior must actually be full: ") {
            CivsSettlementLayout.candidates(city, house.first, house.second, access, frame) shouldBe emptyList()
            CivsSettlementLayout.infill(city, house.first, house.second, access, frame) shouldBe emptyList()
        }
    }

    fun assertCompleteGrowth(grown: CivSettlementPlan, label: String): CivSettlementInfrastructure = withClue("$label: ") {
        CivsSettlementPlans.validate(city, grown)
        grown.halfWidth shouldBe 32
        grown.plots.size shouldBe plan.plots.size + 1
        val house = grown.plots.single { it.building.type == BuildingType.HOUSE }
        val route = grown.routes.getValue(house.building.id.toString())
        route.last() shouldBe house.building.entrance
        route.all { it.y == 65 } shouldBe true
        city.buildings.filterNot { it.type.isInfrastructure }.forEach { paid ->
            val plot = grown.plots.single { it.building.id == paid.id }
            plot.building shouldBe paid.copy(phase = BuildingPhase.SITE)
            plot.envelope shouldBe paid.bounds
        }
        access.routes.forEach { (owner, paid) -> grown.routes.getValue(owner.toString()) shouldBe paid }
        val forecast = city.copy(buildings = city.buildings + house.building,
            autonomy = city.autonomy!!.copy(plan = grown, layoutHalfWidth = grown.halfWidth))
        val frame = CivsSettlementLayout.frame(forecast)
        val built = CivsSettlementLayout.infrastructure(forecast, frame, surface, grown.routes.values.toList(),
            access.infrastructure, retainedRoutes = access.routes.values)
        built.perimeterClosed shouldBe true
        built.rejected shouldBe emptyMap()
        access.infrastructure.forEach { old -> built.pieces.single { it.id == old.id } shouldBe old }
        val interior = CivsSettlementGrowth.interior(frame)
        (civsTerrainKey(house.building.entrance.x, house.building.entrance.z) in interior) shouldBe true
        (civsTerrainKey(house.building.entrance.x, house.building.entrance.z) in
            CivsSettlementGrowth.interior(CivsSettlementLayout.frame(city))) shouldBe false

        // Supplemental 2-D non-air occupancy flood, not native collisions or player movement.
        // Start in the NEW quarter and leave the old gate open, so its ring cannot hide a hole.
        val blocked = built.pieces.filter { it.type in setOf(BuildingType.WALL, BuildingType.GATE) }
            .flatMap { it.blocks }.filter { it.position.y in 65..66 && it.after != "minecraft:air" }
            .mapTo(hashSetOf()) { it.position.x to it.position.z }
        val start = house.building.entrance.let { it.x to it.z }
        retainedGrowthEscapes(start, blocked, city.radius) shouldBe true
        for (gate in frame.gates) {
            val alongX = Math.floorMod(gate.side + frame.rotation / 90, 2) == 1
            if (alongX) for (z in gate.passage.min.z + 1 until gate.passage.max.z) blocked += gate.center.x to z
            else for (x in gate.passage.min.x + 1 until gate.passage.max.x) blocked += x to gate.center.z
        }
        retainedGrowthEscapes(start, blocked, city.radius) shouldBe false
        built
    }
}

private fun retainedGrowthFixture(farmOnNextAxis: Boolean, farmDepth: Int = 5, gradedFarm: Boolean = false,
    southInnerGate: Boolean = false): RetainedGrowthFixture {
    fun template(type: BuildingType, minX: Int, minZ: Int, maxX: Int, maxZ: Int) =
        CivBuilding(UUID(223, 100L + type.ordinal), type, 1, CivPosition(0, 0, 0), 0, "fixture/retained-growth/$type",
            CivBox(CivPosition(minX, 0, minZ), CivPosition(maxX, 7, maxZ)), CivPosition(0, 1, 0), BuildingPhase.CONSTRUCTING)
    val templates = mapOf(
        BuildingType.TOWN_HALL to template(BuildingType.TOWN_HALL, -3, -5, 3, -1),
        BuildingType.BUILDER_HUT to template(BuildingType.BUILDER_HUT, -1, -4, 1, -1),
        BuildingType.WAREHOUSE to template(BuildingType.WAREHOUSE, -1, -4, 1, -1),
        BuildingType.FORESTER to template(BuildingType.FORESTER, -2, -5, 2, -1),
        BuildingType.QUARRY to template(BuildingType.QUARRY, -2, -5, 2, -1),
        BuildingType.FARM to template(BuildingType.FARM, -2, -farmDepth, 2, -1),
        BuildingType.HOUSE to template(BuildingType.HOUSE, -8, -16, 7, -1),
    ).mapValues { (_, building) -> building to building.copy(level = building.type.maxLevel) }
    fun actual(type: BuildingType, x: Int, z: Int, rotation: Int = 0) =
        CivsSettlementPlans.at(templates.getValue(type).first, CivPosition(x, 64, z), rotation).copy(phase = BuildingPhase.ACTIVE)
    val farmEntry = if (farmOnNextAxis) 29 else 17
    val ordinary = listOf(actual(BuildingType.TOWN_HALL, 0, -1), actual(BuildingType.BUILDER_HUT, 6, -3),
        actual(BuildingType.WAREHOUSE, -6, -3), actual(BuildingType.FORESTER, 20, if (southInnerGate) 17 else -15),
        actual(BuildingType.QUARRY, 26, if (southInnerGate) 23 else -23), actual(BuildingType.FARM, 0, farmEntry, 180))
    var city = settlementPlanCity().copy(id = UUID(223, 1), radius = 56, name = "Retained growth public fixture",
        buildings = ordinary, inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy(layoutHalfWidth = 24))
    val routes = mapOf(
        BuildingType.BUILDER_HUT to retainedGrowthRoute(0 to -1, 0 to 0, 6 to 0, 6 to -3),
        BuildingType.WAREHOUSE to retainedGrowthRoute(0 to -1, 0 to 0, -6 to 0, -6 to -3),
        BuildingType.FORESTER to if (southInnerGate) retainedGrowthRoute(0 to -1, 0 to 0, 27 to 0, 27 to 17, 20 to 17)
            else retainedGrowthRoute(0 to -1, 0 to 0, 20 to 0, 20 to -15),
        BuildingType.QUARRY to if (southInnerGate) retainedGrowthRoute(0 to -1, 0 to 0, 27 to 0, 27 to 9, 30 to 9, 30 to 23, 26 to 23)
            else retainedGrowthRoute(0 to -1, 0 to 0, 26 to 0, 26 to -23),
        BuildingType.FARM to (if (southInnerGate) retainedGrowthRoute(0 to -1, 0 to 0, 27 to 0, 27 to 9, 0 to 9, 0 to farmEntry)
            else retainedGrowthRoute(0 to -1, 0 to 0, 20 to 0, 20 to 16, 0 to 16, 0 to farmEntry))
            .map { if (gradedFarm && it.x == 20 && it.z in 8..10) it.copy(y = 66) else it },
    ).mapKeys { (type, _) -> ordinary.single { it.type == type }.id }
    val flat = settlementPlanSurface(city)
    val inner = if (southInnerGate) CivsSettlementLayout.frame(city).copy(gates = listOf(retainedGrowthGate(2, 27, 3)),
        wallObstacles = retainedGrowthRing(-10, -10, 3, 30, gateZ = 27).map { box ->
            CivBox(box.min.copy(x = box.min.z, z = box.min.x), box.max.copy(x = box.max.z, z = box.max.x))
        }) else CivsSettlementLayout.frame(city).copy(gates = listOf(retainedGrowthGate(1, 10, 0)),
            wallObstacles = retainedGrowthRing(-10, -10, 10, 10, gateZ = 0))
    val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, 24,
        ordinary.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
        routes.mapKeys { it.key.toString() }, inner.wallObstacles, inner.gates.map { it.passage }, inner.gates.map { it.side })
    city = city.copy(autonomy = city.autonomy!!.copy(plan = plan))
    val pieces = withClue("SETUP: original paid inner perimeter: ") {
        CivsSettlementLayout.infrastructure(city, inner, flat, routes.values.toList(), pavingRoutes = emptyList(),
            retainedRoutes = routes.values).also { it.perimeterClosed shouldBe true; it.rejected shouldBe emptyMap() }.pieces
    }
    if (southInnerGate) withClue("SETUP: original paid inner perimeter must close at body height: ") {
        val blocked = pieces.flatMap { it.blocks }.filter { it.position.y in 65..66 && it.after != "minecraft:air" }
            .mapTo(hashSetOf()) { it.position.x to it.position.z }
        retainedGrowthEscapes(0 to 0, blocked, city.radius) shouldBe true
        for (x in 26..28) blocked += x to 3
        retainedGrowthEscapes(0 to 0, blocked, city.radius) shouldBe false
    }
    city = city.copy(buildings = ordinary + pieces.map { CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE) })
    val journals = ordinary.mapNotNull { building -> routes[building.id]?.let { paid ->
        CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
            CivBox(CivPosition(-56, 64, -56), CivPosition(56, 66, 56)), emptyList(), paid, 64, 0, 0))
    } } + pieces.map { piece ->
        val building = requireNotNull(city.building(piece.id))
        CivPlacementPlan(CivsGateRefit.retainedPlanId(building), city.id, city.worldId, building,
            piece.blocks.map { CivPlanBlock(it.position, if (it.position.y == 64) "minecraft:grass_block" else "minecraft:air", it.after) })
    }
    val access = CivAutonomousAccess.from(city, journals)
    access.routes shouldBe routes
    access.infrastructure shouldBe pieces
    return RetainedGrowthFixture(city, plan, templates, retainedGrowthSurface(city, access), journals, access)
}

private fun retainedGrowthRoute(vararg bends: Pair<Int, Int>): List<CivPosition> = buildList {
    add(CivPosition(bends.first().first, 65, bends.first().second))
    for (end in bends.drop(1)) {
        val start = last()
        require(start.x == end.first || start.z == end.second)
        val dx = (end.first - start.x).compareTo(0); val dz = (end.second - start.z).compareTo(0)
        for (step in 1..(abs(end.first - start.x) + abs(end.second - start.z)))
            add(CivPosition(start.x + dx * step, 65, start.z + dz * step))
    }
}

/** Constructed height field only: paid paths/steps and generated roofs do not become natural. */
private fun retainedGrowthSurface(city: Settlement, access: CivAutonomousAccess): CivTerrainSurface {
    val flat = settlementPlanSurface(city)
    return flat.copy(columns = flat.columns.toMutableMap().apply {
        for (route in access.routes.values) {
            val profile = CivsAutonomousTerraform.roadProfile(route)
            val floors = profile.mapValues { it.value.y }
            for ((key, floor) in profile) put(key, CivTerrainSurfaceColumn(floor.y, natural = false,
                dirtPath = CivsSettlementLayout.pathPavement(floor, floors, city.style) == "minecraft:dirt_path"))
        }
        access.infrastructure.flatMap { it.blocks }.filter { it.after !in setOf("minecraft:air", "minecraft:barrier") }
            .groupBy { civsTerrainKey(it.position.x, it.position.z) }.forEach { (key, blocks) ->
                put(key, CivTerrainSurfaceColumn(blocks.maxOf { it.position.y }, natural = false))
            }
    })
}

private fun retainedGrowthGate(side: Int, x: Int, z: Int) = CivSettlementGate(side, CivPosition(x, 64, z),
    CivBox(CivPosition(x - 2, 64, z - 2), CivPosition(x + 2, 64, z + 2)))

/** One-column rectangle, with its only opening in the east edge; the generator owns every block. */
private fun retainedGrowthRing(minX: Int, minZ: Int, maxX: Int, maxZ: Int, gateZ: Int): List<CivBox> {
    fun box(x0: Int, z0: Int, x1: Int, z1: Int) = CivBox(CivPosition(x0, 64, z0), CivPosition(x1, 64, z1))
    return listOf(box(minX, minZ, maxX, minZ), box(minX, maxZ, maxX, maxZ),
        box(minX, minZ + 1, minX, maxZ - 1), box(maxX, minZ + 1, maxX, gateZ - 3),
        box(maxX, gateZ + 3, maxX, maxZ - 1))
        // The full horizontal edge already owns its corner when a gate meets that end.
        .filter { it.min.x <= it.max.x && it.min.z <= it.max.z }
}

private fun retainedGrowthEscapes(start: Pair<Int, Int>, blocked: Set<Pair<Int, Int>>, radius: Int): Boolean {
    val seen = hashSetOf(start)
    val queue = ArrayDeque<Pair<Int, Int>>().apply { add(start) }
    while (queue.isNotEmpty()) {
        val (x, z) = queue.removeFirst()
        if (abs(x) >= radius || abs(z) >= radius) return true
        for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            val next = x + dx to z + dz
            if (next !in blocked && seen.add(next)) queue.add(next)
        }
    }
    return false
}
