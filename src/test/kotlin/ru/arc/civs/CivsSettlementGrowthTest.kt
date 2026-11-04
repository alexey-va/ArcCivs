package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSettlementGrowthTest : FunSpec({
    test("new Caledonia founding starts at a hall-compatible thirty two block quarter without forecasting the maximum town") {
        val base = growthCity()
        val hall = base.buildings.single()
        val city = CivsAutonomy.found(base.id, base.owner, base.name, base.worldId, base.center, 96,
            base.style, hall, 1_000).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        city.autonomy!!.layoutHalfWidth shouldBe 32
        CivsSettlementLayout.frame(city).halfWidth shouldBe 32
        CivsSettlementGrowth.profile(city) shouldBe listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT)
        val before = Gson().toJson(city)
        val plan = growthPlan(city)
        plan.halfWidth shouldBe 32
        plan.plots.map { it.building.type }.toSet() shouldBe setOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT)
        plan.plots.size shouldBe 2
        CivsSettlementGrowth.covers(city, plan) shouldBe true
        CivsVillageSiteScoring.completeProfile(plan) shouldBe false
        Gson().toJson(city) shouldBe before
    }

    test("native sized houses expand a filled quarter through ordinary outer infrastructure while paid inner walls stay fixed") {
        var city = growthCity()
        val templates = growthTemplates()
        val first = growthPlan(city)
        city = city.copy(buildings = first.plots.map { it.building.copy(phase = BuildingPhase.ACTIVE) },
            autonomy = city.autonomy!!.copy(plan = first, layoutHalfWidth = first.halfWidth))
        val surface = settlementPlanSurface(city)
        val oldPieces = CivsSettlementLayout.infrastructure(city, CivsSettlementLayout.frame(city), surface,
            first.routes.values.toList()).also { it.perimeterClosed shouldBe true; it.rejected shouldBe emptyMap() }.pieces
        val oldBuildings = oldPieces.map { CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE) }
        city = city.copy(buildings = city.buildings + oldBuildings)
        var plan = first
        val widths = mutableListOf(plan.halfWidth)
        repeat(8) {
            val before = city
            val access = CivAutonomousAccess(plan.routes.mapKeys { UUID.fromString(it.key) }, oldPieces)
            val profile = city.buildings.filterNot { it.type.isInfrastructure }.map { it.type } + BuildingType.HOUSE
            plan = try { CivsSettlementPlans.prepareGrowth(city, templates, surface, access, profile) }
                catch (failure: CivsRejection) { throw AssertionError("house=$it widths=$widths ${failure.reason}: ${failure.values}", failure) }
            (plan.halfWidth >= widths.last()) shouldBe true
            widths += plan.halfWidth
            before.buildings.forEach { actual ->
                if (!actual.type.isInfrastructure) plan.plots.single { it.building.id == actual.id }.building shouldBe
                    actual.copy(phase = BuildingPhase.SITE)
            }
            city = city.copy(buildings = oldBuildings + plan.plots.map { it.building.copy(phase = BuildingPhase.ACTIVE) },
                autonomy = city.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth))
            city.buildings.filter { it.type.isInfrastructure } shouldBe oldBuildings
            CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java)) shouldBe city
        }
        (widths.last() > 32) shouldBe true
        val outer = CivsSettlementLayout.infrastructure(city, CivsSettlementLayout.frame(city), surface,
            plan.routes.values.toList(), oldPieces, retainedRoutes = first.routes.values)
        outer.perimeterClosed shouldBe true
        outer.rejected shouldBe emptyMap()
        oldPieces.forEach { old -> outer.pieces.single { it.id == old.id } shouldBe old }
        outer.pieces.any { it.type == BuildingType.WALL && it.id !in oldPieces.map { old -> old.id } } shouldBe true
    }

    test("an engulfed paid mine stays usable while a new mine reservation must be outside the current wall") {
        val base = growthCity().copy(radius = 128, autonomy = CivAutonomy(layoutHalfWidth = 56))
        val mine = CivsSettlementPlans.at(growthTemplates().getValue(BuildingType.QUARRY).first,
            CivPosition(30, 64, 0), 90, UUID(206, 90)).copy(phase = BuildingPhase.ACTIVE)
        val futureMine = CivsSettlementPlans.at(growthTemplates().getValue(BuildingType.QUARRY).first,
            CivPosition(-30, 64, 0), 270, UUID(206, 91)).copy(phase = BuildingPhase.SITE)
        val frame = CivsSettlementLayout.frame(base)
        val old = CivSettlementPlan(CivsSettlementPlans.VERSION, 0, frame.halfWidth,
            listOf(CivSettlementPlot(base.buildings.single().copy(phase = BuildingPhase.SITE), base.buildings.single().bounds),
                CivSettlementPlot(mine.copy(phase = BuildingPhase.SITE), mine.bounds), CivSettlementPlot(futureMine, futureMine.bounds)),
            emptyMap(), frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
        val city = base.copy(buildings = base.buildings + mine, autonomy = base.autonomy!!.copy(plan = old))
        CivsSettlementPlans.current(city) shouldBe old
        CivsSettlementPlans.next(city, BuildingType.QUARRY) shouldBe null
        CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java)).building(mine.id) shouldBe mine
        val candidates = CivsSettlementLayout.candidates(city, growthTemplates().getValue(BuildingType.QUARRY).first,
            growthTemplates().getValue(BuildingType.QUARRY).second)
        candidates.isNotEmpty() shouldBe true
        candidates.all { CivsSettlementGrowth.outside(city, it.building.bounds) } shouldBe true
    }

    test("a saved wide town retains its accepted frame and protection radius after upgrading the defaults") {
        val city = growthCity().copy(radius = 128, autonomy = CivAutonomy(layoutHalfWidth = 74))
        val restored = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
        restored.radius shouldBe 128
        CivsSettlementLayout.frame(restored).halfWidth shouldBe 74
        CivsSettlementGrowth.halfWidths(restored) shouldBe listOf(74)
    }

    test("irregular accepted walls own the real interior rather than their larger square canvas") {
        val city = growthCity()
        val large = CivsSettlementLayout.frame(city)
        val small = CivsSettlementLayout.frame(city, 24)
        val irregular = large.copy(wallObstacles = small.wallObstacles, gates = small.gates)
        val interior = CivsSettlementGrowth.interior(irregular)
        (civsTerrainKey(0, 0) in interior) shouldBe true
        (civsTerrainKey(28, 0) in interior) shouldBe false
        val template = growthTemplates().getValue(BuildingType.HOUSE)
        CivsSettlementLayout.candidates(city, template.first, template.second, frame = irregular).all { candidate ->
            (candidate.building.bounds.min.x..candidate.building.bounds.max.x).all { x ->
                (candidate.building.bounds.min.z..candidate.building.bounds.max.z).all { z -> civsTerrainKey(x, z) in interior }
            }
        } shouldBe true
    }

    test("oversized retained wall metadata cannot turn the detached interior check into an unbounded scan") {
        val frame = CivsSettlementLayout.frame(growthCity())
        val oversized = frame.copy(wallObstacles = listOf(CivBox(CivPosition(-100_000, 64, -100_000),
            CivPosition(100_000, 64, 100_000))))
        CivsSettlementGrowth.interior(oversized) shouldBe emptySet()
    }
})

private fun growthCity(): Settlement {
    val base = settlementPlanCity()
    val hall = CivsSettlementPlans.at(growthTemplates().getValue(BuildingType.TOWN_HALL).first,
        CivPosition(0, 64, 0), 0, base.buildings.single().id).copy(phase = BuildingPhase.ACTIVE)
    return base.copy(style = "caledonia", buildings = listOf(hall), inventory = CivInventory(CivStorageMode.PHYSICAL),
        autonomy = CivAutonomy(layoutHalfWidth = 32))
}

/** Metadata only, verified from the selected private catalog on 2026-11-04; no schematic voxels. */
private fun growthTemplates(): Map<BuildingType, Pair<CivBuilding, CivBuilding>> = settlementPlanTemplates().mapValues { (type, pair) ->
    val sizeOrigin = when (type) {
        BuildingType.TOWN_HALL -> Triple(CivPosition(21, 37, 22), CivPosition(9, 1, 22), "hall")
        BuildingType.BUILDER_HUT -> Triple(CivPosition(15, 18, 16), CivPosition(7, 5, 16), "builder")
        BuildingType.HOUSE -> Triple(CivPosition(18, 20, 16), CivPosition(10, 1, 16), "resalta")
        BuildingType.WAREHOUSE -> Triple(CivPosition(21, 19, 22), CivPosition(9, 1, 22), "warehouse")
        BuildingType.QUARRY -> Triple(CivPosition(21, 24, 22), CivPosition(10, 6, 22), "quarry")
        else -> return@mapValues pair
    }
    val (size, origin, family) = sizeOrigin
    val bounds = CivBox(CivPosition(-origin.x, -origin.y, -origin.z),
        CivPosition(size.x - origin.x - 1, size.y - origin.y - 1, size.z - origin.z - 1))
    val first = pair.first.copy(bounds = bounds, schematicId = "civs/v2/caledonia/$family/l1")
    first to first.copy(level = type.maxLevel)
}

private fun growthPlan(city: Settlement): CivSettlementPlan =
    CivsSettlementPlans.prepareGrowth(city, growthTemplates(), settlementPlanSurface(city), CivAutonomousAccess(),
        CivsSettlementGrowth.profile(city))
