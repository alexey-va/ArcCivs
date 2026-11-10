package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID

class CivsSettlementPlanGrowthTest : FunSpec({
    val legacy by lazy { legacyGrowthCity() }

    test("a roomy sixteen plot plan appends eighteen plots without moving old unpaid intent or its perimeter") {
        val before = legacy
        val old = requireNotNull(CivsSettlementPlans.current(before))
        old.plots.size shouldBe 16
        old.halfWidth shouldBe 56
        old.routes.values.flatten().none { point -> old.walls.any { CivsAutonomousGeometry.horizontalContains(it, point) } } shouldBe true
        CivsSettlementLayout.frame(before.copy(autonomy = before.autonomy!!.copy(layoutHalfWidth = null))).halfWidth shouldBe 56
        CivsSettlementPlans.needsExtension(before) shouldBe true
        val templates = settlementPlanTemplates()
        val surface = settlementPlanSurface(before)
        val next = try {
            CivsSettlementPlans.prepare(before, templates, surface, CivAutonomousAccess(),
                inspect = layoutFixtureDiagnostics("growth", before, templates, surface))
        } catch (failure: CivsRejection) { throw AssertionError("${failure.reason}: ${failure.values}", failure) }
        next.plots.size shouldBe 34
        next.plots.count { it.building.type == BuildingType.HOUSE } shouldBe 16
        next.plots.take(old.plots.size) shouldBe old.plots
        next.walls shouldBe old.walls
        next.gates shouldBe old.gates
        next.gateSides shouldBe old.gateSides
        next.halfWidth shouldBe 56
        old.routes.forEach { (id, route) -> next.routes[id] shouldBe route }
        val adopted = before.copy(autonomy = before.autonomy!!.copy(plan = next))
        CivsSettlementPlans.needsExtension(adopted) shouldBe false
        CivsSettlementLayout.frame(adopted).halfWidth shouldBe 56
        CivsRules.validate(Gson().fromJson(Gson().toJson(adopted), Settlement::class.java)) shouldBe adopted
        before.autonomy!!.plan shouldBe old
        before.buildings.size shouldBe 1
        before.stock shouldBe Stock()
        before.workers shouldBe emptyList()
    }

    test("successive ordinary perimeters retain exact completed journals and keep a paved radial crossing") {
        val initial = settlementPlanCity()
        val hall = initial.buildings.single()
        val forester = CivBuilding(UUID(94, 5), BuildingType.FORESTER, 1, CivPosition(0, 64, 100), 0, "fixture/forester",
            CivBox(CivPosition(-2, 64, 100), CivPosition(2, 70, 103)), CivPosition(0, 65, 99), BuildingPhase.ACTIVE)
        val base = initial.copy(style = "medievaloak", radius = 120, buildings = listOf(hall, forester),
            autonomy = CivAutonomy(layoutHalfWidth = 24))
        val route = (0..forester.entrance.z).map { CivPosition(0, 65, it) }
        val flat = settlementPlanSurface(base)
        val surface = flat.copy(columns = flat.columns + route.associate {
            civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(64, false, dirtPath = true)
        })
        val radial = CivPlacementPlan(forester.id, base.id, base.worldId, forester.copy(phase = BuildingPhase.CONSTRUCTING),
            listOf(CivPlanBlock(forester.bounds.min, "minecraft:air", "minecraft:cobblestone")), "a".repeat(64),
            CivTerraformPlan(forester.bounds, emptyList(), route, 64, 0, 0))
        val frame = CivsSettlementLayout.frame(base)
        val seeded = CivsSettlementLayout.infrastructure(base, frame, surface, listOf(route), pavingRoutes = emptyList(),
            retainedRoutes = listOf(route), retainedAccess = CivAutonomousAccess.from(base, listOf(radial)), protectPaidGateFloors = true)
        seeded.perimeterClosed shouldBe true
        val first = CivSettlementPlan(CivsSettlementPlans.VERSION, 3, frame.halfWidth,
            base.buildings.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
            mapOf(forester.id.toString() to route), frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side },
            perimeterEntrances = seeded.pieces.associate { it.id.toString() to it.entrance })
        fun captured(plan: CivSettlementPlan, layout: CivSettlementInfrastructure): List<CivPlacementPlan> =
            requireNotNull(plan.perimeterEntrances).map { (id, stance) ->
                val piece = layout.pieces.single { it.id.toString() == id }.copy(entrance = stance)
                val capture = CivsInfrastructure.building(piece)
                CivPlacementPlan(capture.id, base.id, base.worldId, capture,
                    piece.blocks.filterNot { it.after == "minecraft:barrier" }.map {
                        CivPlanBlock(it.position, "minecraft:air", it.after)
                    }, CivsGateRefit.sha256(CivsInfrastructure.schematic(piece, 4671)))
            }
        fun adopt(city: Settlement, plan: CivSettlementPlan, newCaptures: List<CivPlacementPlan> = emptyList()) =
            city.copy(autonomy = requireNotNull(city.autonomy).copy(plan = plan, layoutHalfWidth = plan.halfWidth),
                buildings = city.buildings + newCaptures.map { it.building.copy(phase = BuildingPhase.ACTIVE) })
        val oldCaptures = captured(first, seeded)
        val inside = adopt(base, first, oldCaptures)
        val gson = Gson()
        gson.fromJson(gson.toJson(inside), Settlement::class.java).autonomy?.plan?.completedPerimeters shouldBe null
        fun plan(city: Settlement, width: Int, journals: List<CivPlacementPlan>,
            inspect: ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)? = null) =
            CivsSettlementPlans.prepare(city, emptyMap(), surface, CivAutonomousAccess.from(city, journals + radial),
                profile = base.buildings.map { it.type }, halfWidth = width, inspect = inspect)

        val missing = shouldThrow<CivsRejection> { plan(inside, 48, oldCaptures.dropLast(1)) }
        missing.values["stage"] shouldBe "retained-growth-old-incomplete"
        lateinit var middleLayout: CivSettlementInfrastructure
        val middle = plan(inside, 48, oldCaptures) { _, layout -> middleLayout = layout }
        val archived = requireNotNull(middle.completedPerimeters).single()
        archived.planRevision shouldBe first.revision
        archived.walls shouldBe first.walls
        archived.gates shouldBe first.gates
        archived.captures shouldBe oldCaptures.associate { it.building.id.toString() to
            CivPerimeterCapture(it.id, requireNotNull(it.capturedSha256)) }
        middle.routes[forester.id.toString()] shouldBe route
        val outerGate = middleLayout.pieces.single { piece -> piece.type == BuildingType.GATE &&
            piece.id.toString() in requireNotNull(middle.perimeterEntrances) && piece.paidGateFloors.isNotEmpty() }
        outerGate.paidGateFloors.size shouldBe 15
        outerGate.blocks.single { it.position == CivPosition(0, 64, 48) }.after shouldBe "minecraft:barrier"
        middle.perimeterEntrances?.get(outerGate.id.toString())?.x shouldBe 0
        val pending = adopt(inside, middle)
        shouldThrow<CivsRejection> { plan(pending, 72, oldCaptures) }.values["stage"] shouldBe "retained-growth-old-incomplete"
        val middleCaptures = captured(middle, middleLayout)
        val two = gson.fromJson(gson.toJson(adopt(inside, middle, middleCaptures)), Settlement::class.java)
        CivsSettlementPlans.current(two) shouldBe middle
        val outer = plan(two, 72, oldCaptures + middleCaptures)
        outer.completedPerimeters?.map { it.halfWidth } shouldBe listOf(24, 48)
        outer.completedPerimeters?.first() shouldBe archived
        outer.completedPerimeters?.last()?.captures?.keys shouldBe middle.perimeterEntrances?.keys
        outer.routes[forester.id.toString()] shouldBe route
        outer.perimeterEntrances?.isNotEmpty() shouldBe true
        two.buildings.size shouldBe inside.buildings.size + middleCaptures.size
    }

    test("a compact retained subset adds the next house without moving paid town geometry or invalidating accepted work") {
        // The current irregular enclosure is authoritative; add one needed plot rather than forcing all future houses inside it.
        val before = legacyGrowthCity(roomy = false)
        val old = requireNotNull(CivsSettlementPlans.current(before))
        val serialized = Gson().toJson(before)
        val requested = old.plots.map { it.building.type } + BuildingType.HOUSE
        val next = CivsSettlementPlans.prepareGrowth(before, settlementPlanTemplates(), settlementPlanSurface(before),
            CivAutonomousAccess(), requested)
        next.plots.size shouldBe requested.size
        next.plots.groupingBy { it.building.type }.eachCount() shouldBe requested.groupingBy { it }.eachCount()
        before.buildings.forEach { paid ->
            civsSameBuildingGeometry(next.plots.single { it.building.id == paid.id }.building,
                paid.copy(phase = BuildingPhase.SITE)) shouldBe true
        }
        (next.halfWidth >= old.halfWidth) shouldBe true
        CivsSettlementPlans.validate(before, next)
        val adopted = before.copy(autonomy = before.autonomy!!.copy(plan = next))
        CivsSettlementGrowth.covers(adopted, next) shouldBe true
        CivsRules.validate(adopted) shouldBe adopted
        Gson().toJson(before) shouldBe serialized
        CivsSettlementPlans.current(before) shouldBe old
        before.revision shouldBe 0L
        before.constructionQueue shouldBe null
        CivsRules.validate(before) shouldBe before

        val plot = requireNotNull(CivsSettlementPlans.next(before, BuildingType.HOUSE))
        val admitted = CivsRules.placeSite(before, before.owner, plot.building)
        val queued = CivsRules.enqueue(admitted, admitted.owner, plot.building.id)
        queued.constructionQueue shouldBe listOf(plot.building.id)
        queued.autonomy!!.plan shouldBe old
        CivsSettlementPlans.needsExtension(queued) shouldBe false
        CivsRules.validate(queued) shouldBe queued

        val templates = settlementPlanTemplates()
        val established = before.copy(buildings = old.plots.map {
            CivsSettlementPlans.at(templates.getValue(it.building.type).second, it.building.anchor,
                it.building.rotation, it.building.id).copy(phase = BuildingPhase.ACTIVE)
        }, research = CivResearchState(CivResearchId.entries.toSet()))
        val forester = established.buildings.first { it.type == BuildingType.FORESTER }.copy(level = 2)
        val upgradeable = established.copy(buildings = established.buildings.map { if (it.id == forester.id) forester else it })
        CivsRules.validate(upgradeable) shouldBe upgradeable
        CivsAutonomy.nextGoal(upgradeable)?.let { Triple(it.type, it.targetLevel, it.buildingId) } shouldBe
            Triple(BuildingType.FORESTER, 3, forester.id)
        upgradeable.autonomy!!.plan shouldBe old
    }

    test("an obstructed new gate defers the perimeter without losing the warehouse or paid approaches") {
        val (city, forecast, access) = deferredWarehouseFixture()
        val warehouse = forecast.plots.single { it.building.type == BuildingType.WAREHOUSE }.building
        val old = Gson().toJson(city)
        val after = forecastWithRejectedGate(city, forecast, access)
        after.deferredPerimeter shouldBe CivDeferredPerimeter("gate-junction", warehouse.id, warehouse.anchor, city.center)
        after.routes shouldBe forecast.routes
        after.plots shouldBe forecast.plots
        after.walls shouldBe forecast.walls
        after.gates shouldBe forecast.gates
        Gson().toJson(city) shouldBe old
        val saved = city.copy(autonomy = requireNotNull(city.autonomy).copy(plan = after, layoutHalfWidth = after.halfWidth))
        val loaded = Gson().fromJson(Gson().toJson(saved), Settlement::class.java)
        CivsSettlementPlans.current(loaded) shouldBe after
        CivsSettlementPlans.next(loaded, BuildingType.WAREHOUSE)?.building shouldBe warehouse
        loaded.construction shouldBe null
        loaded.stock shouldBe city.stock

        val paidRoutes = access.routes.values
        val roads = paidRoutes.flatMapTo(hashSetOf()) { CivsAutonomousTerraform.roadProfile(it).keys }
        val safe = CivsSettlementLayout.infrastructure(loaded, CivsSettlementLayout.frame(loaded),
            settlementPlanSurface(loaded), after.routes.values.toList(), retainedRoutes = paidRoutes)
        safe.perimeterClosed shouldBe false
        safe.rejected["deferred-gate"] shouldBe after.gates.size
        safe.pieces.none { it.type == BuildingType.GATE } shouldBe true
        val walls = safe.pieces.filter { it.type == BuildingType.WALL }
        walls.isNotEmpty() shouldBe true
        walls.flatMap { it.blocks }.none { civsTerrainKey(it.position.x, it.position.z) in roads } shouldBe true
    }

    test("a fully closed later forecast wins, and a deferred plan never replaces a paid approach") {
        val (city, forecast, access) = deferredWarehouseFixture()
        val complete = forecastWithRejectedGate(city, forecast, access, laterClosed = true)
        complete.deferredPerimeter shouldBe null
        complete.halfWidth shouldBe 64

        val (id, route) = access.routes.entries.first()
        val altered = forecast.copy(routes = forecast.routes + (id.toString() to route.map { it.copy(y = it.y + 1) }))
        val failure = shouldThrow<CivsRejection> { forecastWithRejectedGate(city, altered, access) }
        failure.values["stage"] shouldBe "gate-junction"
        CivsSettlementPlans.next(city, BuildingType.WAREHOUSE) shouldBe null
    }

    test("a blocked perimeter can defer after a paid gate and still admit the next house") {
        val (base, warehousePlan, _) = deferredWarehouseFixture()
        val plan = warehousePlan.copy(halfWidth = 72)
        val warehouse = plan.plots.single { it.building.type == BuildingType.WAREHOUSE }.building
        val gateBounds = plan.gates.first()
        val gateId = UUID(94, 900)
        val gate = CivSettlementPiece(gateId, BuildingType.GATE, gateBounds, gateBounds.min, emptyList())
        val city = base.copy(buildings = base.buildings + warehouse.copy(phase = BuildingPhase.ACTIVE) +
            CivsInfrastructure.building(gate).copy(phase = BuildingPhase.ACTIVE),
            inventory = CivInventory(CivStorageMode.PHYSICAL),
            autonomy = requireNotNull(base.autonomy).copy(layoutHalfWidth = 72, plan = plan))
        val access = CivAutonomousAccess(plan.routes.mapKeys { UUID.fromString(it.key) }, listOf(gate))
        val withoutJournal = access.copy(routes = access.routes - warehouse.id)
        withoutJournal.roadColumns(city) shouldBe access.roadColumns(city)
        val surface = settlementPlanSurface(city)
        val templates = settlementPlanTemplates()
        val profile = CivsSettlementGrowth.profile(city)
        profile.last() shouldBe BuildingType.HOUSE
        mockkObject(CivsSettlementPlans)
        try {
            every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "infrastructure", "infrastructure-slope" to "1"))
            val grown = CivsSettlementPlans.prepareGrowth(city, templates, surface, access, profile)
            val house = grown.plots.single { it.building.type == BuildingType.HOUSE }
            grown.deferredPerimeter?.plotId shouldBe house.building.id
            grown.deferredPerimeter?.stage shouldBe CivsSettlementPlans.HOUSING_PERIMETER
            access.routes.forEach { (id, route) -> grown.routes[id.toString()] shouldBe route }
            CivsAutonomousGeometry.overlaps(house.envelope, gate.bounds) shouldBe false
            val route = grown.routes.getValue(house.building.id.toString())
            route.last() shouldBe house.building.entrance
            val unavailable = surface.copy(columns = surface.columns - surface.columns.keys.first())
            shouldThrow<CivsRejection> {
                CivsSettlementPlans.prepareGrowth(city, templates, unavailable, access, profile)
            }.reason shouldBe "autonomy-layout-obstructed"
        } finally {
            unmockkObject(CivsSettlementPlans)
        }
    }

    test("a surveyed Hall II city can admit its next university without rebuilding a paid perimeter") {
        val old = requireNotNull(CivsSettlementPlans.current(legacy))
        val plots = old.plots.filter { it.building.type in CivsAutonomy.baseProfile }
        val routes = old.routes.filterKeys { id -> plots.any { it.building.id.toString() == id } }
        val prior = old.copy(plots = plots, routes = routes)
        val gateBounds = prior.gates.first()
        val gate = CivSettlementPiece(UUID(94, 901), BuildingType.GATE, gateBounds, gateBounds.min, emptyList())
        val city = legacy.copy(radius = 128, inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = plots.map { it.building.copy(level = minOf(it.building.type.maxLevel, 2), phase = BuildingPhase.ACTIVE) } +
                CivsInfrastructure.building(gate).copy(phase = BuildingPhase.ACTIVE),
            autonomy = requireNotNull(legacy.autonomy).copy(plan = prior))
        val access = CivAutonomousAccess(routes.mapKeys { UUID.fromString(it.key) }, listOf(gate))
        val profile = CivsSettlementGrowth.profile(city)
        profile.last() shouldBe BuildingType.UNIVERSITY
        val surface = settlementPlanSurface(city.copy(radius = 127))
        val templates = settlementPlanTemplates()
        mockkObject(CivsSettlementPlans)
        try {
            every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
                CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "infrastructure", "infrastructure-slope" to "13"))
            val grown = CivsSettlementPlans.prepareGrowth(city, templates, surface, access, profile)
            val university = grown.plots.single { it.building.type == BuildingType.UNIVERSITY }
            grown.deferredPerimeter?.plotId shouldBe university.building.id
            access.routes.forEach { (id, route) -> grown.routes[id.toString()] shouldBe route }
            CivsAutonomousGeometry.overlaps(university.envelope, gate.bounds) shouldBe false
            val route = grown.routes.getValue(university.building.id.toString())
            val protected = access.roadColumns(city)
            val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(university.building.copy(bounds = university.envelope), 0),
                surface, access, grown.walls, reservedRoute = route, protectedRoadColumns = protected)
            draft.columns.none { !it.preserve && civsTerrainKey(it.x, it.z) in protected } shouldBe true
            val incomplete = surface.copy(columns = surface.columns - surface.columns.keys.first())
            shouldThrow<CivsRejection> { CivsSettlementPlans.prepareGrowth(city, templates, incomplete, access, profile) }
                .values["infrastructure-slope"] shouldBe "13"
        } finally {
            unmockkObject(CivsSettlementPlans)
        }
    }

    test("a paid catalog family keeps its shared upgrade footprint when the city prefers another family") {
        val base = settlementPlanCity()
        val templates = settlementPlanTemplates()
        val (first, last) = templates.getValue(BuildingType.UNIVERSITY)
        val compact = CivsSettlementPlans.at(first.copy(
            schematicId = "civs/v2/acacia/education-communitycollege/3a78844a2ef09e51183e/level1"),
            CivPosition(24, 64, -24), 270).copy(phase = BuildingPhase.ACTIVE)
        val preferred = last.copy(schematicId = "civs/v2/medievaloak/education-university/48c5844d7ebe5a1c05af/level3")
        val city = base.copy(buildings = base.buildings + compact)
        val profile = city.buildings.map { it.type }
        val grown = CivsSettlementPlans.prepare(city, templates + (BuildingType.UNIVERSITY to (first to preferred)),
            settlementPlanSurface(city), CivAutonomousAccess(), profile = profile, halfWidth = 56)
        grown.plots.single { it.building.id == compact.id }.envelope shouldBe compact.bounds
    }

    test("accepted orders and FIFO entries defer extension and a failed search keeps all old intent") {
        val before = legacy
        val plot = requireNotNull(CivsSettlementPlans.next(before, BuildingType.HOUSE))
        val queued = before.copy(buildings = before.buildings + plot.building,
            constructionQueue = listOf(plot.building.id))
        CivsSettlementPlans.needsExtension(queued) shouldBe false
        val order = CivConstruction(plot.building.id, plot.building.id, 1, 1, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = plot.building)
        CivsSettlementPlans.needsExtension(before.copy(construction = order)) shouldBe false
        val blocked = settlementPlanSurface(before).let { it.copy(columns = it.columns.mapValues { (_, column) ->
            column.copy(natural = false)
        }) }
        val snapshot = Gson().toJson(before)
        shouldThrow<CivsRejection> {
            CivsSettlementPlans.prepare(before, settlementPlanTemplates(), blocked, CivAutonomousAccess())
        }.reason shouldBe "autonomy-no-site"
        Gson().toJson(before) shouldBe snapshot
        CivsSettlementPlans.current(before) shouldBe before.autonomy!!.plan
    }
})

/** Build a real valid parcel and approach first; only the new perimeter forecast outcome is stubbed. */
private fun deferredWarehouseFixture(): Triple<Settlement, CivSettlementPlan, CivAutonomousAccess> {
    val base = legacyGrowthCity().copy(radius = 96)
    val source = requireNotNull(CivsSettlementPlans.current(base))
    val types = setOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER,
        BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE)
    val plots = source.plots.filter { it.building.type in types }.distinctBy { it.building.type }
    check(plots.size == types.size)
    val kept = plots.filterNot { it.building.type == BuildingType.WAREHOUSE }
    val ids = kept.map { it.building.id.toString() }.toSet()
    val paid = source.routes.filterKeys { it in ids }
    val city = base.copy(buildings = kept.map { it.building.copy(phase = BuildingPhase.ACTIVE) },
        autonomy = requireNotNull(base.autonomy).copy(plan = source.copy(plots = kept, routes = paid)))
    val forecast = source.copy(plots = plots, routes = source.routes.filterKeys { id ->
        plots.any { it.building.id.toString() == id }
    })
    return Triple(city, forecast, CivAutonomousAccess(paid.mapKeys { UUID.fromString(it.key) }))
}

private fun forecastWithRejectedGate(city: Settlement, forecast: CivSettlementPlan, access: CivAutonomousAccess,
    laterClosed: Boolean = false): CivSettlementPlan {
    mockkObject(CivsSettlementPlans)
    return try {
        every { CivsSettlementPlans.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val width = arg<Int>(6)
            val candidate = forecast.copy(halfWidth = width)
            if (laterClosed && width > forecast.halfWidth) candidate else {
                arg<((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)?>(8)
                    ?.invoke(candidate, CivSettlementInfrastructure(emptyList(), mapOf("perimeter-failed" to 1), false))
                throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "gate-junction",
                    "first" to "${city.center.x},${city.center.z}"))
            }
        }
        CivsSettlementPlans.prepareGrowth(city, emptyMap(), CivTerrainSurface(-64, 320, emptyMap()), access,
            CivsSettlementGrowth.profile(city))
    } finally {
        unmockkObject(CivsSettlementPlans)
    }
}

/** A saved V1 profile: the old three houses and all other ordinary plots keep their original identities. */
internal fun legacyGrowthCity(roomy: Boolean = true): Settlement {
    val city = settlementPlanCity().copy(autonomy = CivAutonomy(layoutHalfWidth = 56))
    val plan = CivsSettlementPlans.prepare(city, settlementPlanTemplates(), settlementPlanSurface(city), CivAutonomousAccess())
    val needed = (CivsAutonomy.profile + List(2) { BuildingType.HOUSE }).toMutableList()
    val retained = plan.plots.filter { needed.remove(it.building.type) }
    val removed = plan.plots.filterNot { it in retained }.mapTo(hashSetOf()) { it.building.id.toString() }
    val routes = plan.routes.filterKeys { it !in removed }
    val frame = if (roomy) {
        val initial = CivsSettlementLayout.frame(city)
        // The old ring is authored before adoption. Derive all its openings from the roads it actually retains.
        val closed = initial.copy(gates = emptyList(), wallObstacles = initial.wallObstacles + initial.gates.map { gate ->
            if (gate.side % 2 == 0) gate.passage.copy(min = gate.passage.min.copy(z = gate.center.z - 1),
                max = gate.passage.max.copy(z = gate.center.z + 1)) else
                gate.passage.copy(min = gate.passage.min.copy(x = gate.center.x - 1), max = gate.passage.max.copy(x = gate.center.x + 1))
        })
        CivsPerimeterCrossings.adapt(closed, routes.values, emptyList()).let { aligned -> aligned.copy(gates = aligned.gates.map { gate ->
            gate.copy(side = initial.gates.minBy { kotlin.math.abs(it.center.x - gate.center.x) +
                kotlin.math.abs(it.center.z - gate.center.z) }.side)
        }) }
    } else null
    return city.copy(autonomy = city.autonomy!!.copy(plan = plan.copy(plots = retained, routes = routes,
        walls = frame?.wallObstacles ?: plan.walls, gates = frame?.gates?.map { it.passage } ?: plan.gates,
        gateSides = frame?.gates?.map { it.side } ?: plan.gateSides)))
}
