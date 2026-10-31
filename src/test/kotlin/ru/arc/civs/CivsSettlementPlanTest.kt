package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CivsSettlementPlanTest : FunSpec({
    test("adopted plan reserves the complete profile and real closed infrastructure without granting anything") {
        val city = settlementPlanCity().copy(radius = 128)
        val before = Gson().toJson(city)
        val surface = settlementPlanSurface(city.copy(radius = 127)) // Native surveys retain a bounded 255-square province.
        val plan = planWithDiagnostics { CivsSettlementPlans.prepare(city, settlementPlanTemplates(), surface, CivAutonomousAccess()) }
        plan.plots.size shouldBe 34
        plan.plots.count { it.building.type == BuildingType.HOUSE } shouldBe 16
        plan.plots.count { it.building.type == BuildingType.FORESTER } shouldBe 2
        plan.plots.count { it.building.type == BuildingType.QUARRY } shouldBe 2
        plan.plots.count { it.building.type == BuildingType.FARM } shouldBe 3
        plan.plots.count { it.building.type == BuildingType.WAREHOUSE } shouldBe 2
        plan.plots.takeLast(16).all { it.building.type == BuildingType.HOUSE } shouldBe true
        plan.halfWidth shouldBe 74
        plan.plots.map { it.building.type }.toSet() shouldBe CivsAutonomy.profile.toSet()
        (plan.gates.size in 1..8) shouldBe true
        (plan.walls.isNotEmpty()) shouldBe true
        Gson().toJson(city) shouldBe before
        city.buildings.size shouldBe 1
        val adopted = city.copy(autonomy = city.autonomy!!.copy(plan = plan, layoutHalfWidth = plan.halfWidth))
        val runtimeFrame = CivsSettlementLayout.frame(adopted)
        plan.plots.filter { it.building.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER) }.forEach {
            CivsAutonomousGeometry.overlaps(it.envelope, runtimeFrame.core) shouldBe true
        }
        runtimeFrame.wallObstacles shouldBe plan.walls
        runtimeFrame.gates.map { it.passage } shouldBe plan.gates
        plan.routes.values.all { CivsSettlementLayout.acceptsRoute(runtimeFrame, it) } shouldBe true
        plan.walls.all { wall -> plan.plots.none { CivsAutonomousGeometry.overlaps(wall, it.envelope) } } shouldBe true
        plan.walls.forEach { wall ->
            for (x in wall.min.x..wall.max.x) for (z in wall.min.z..wall.max.z) surface.at(x, z)!!.natural shouldBe true
        }
        val next = CivsSettlementPlans.next(adopted, BuildingType.BUILDER_HUT)!!
        val restored = Gson().fromJson(Gson().toJson(adopted), Settlement::class.java)
        CivsSettlementPlans.current(restored) shouldBe plan
        CivsSettlementPlans.next(restored, BuildingType.BUILDER_HUT) shouldBe next
        val snapshot = civsPlanBoardSnapshot(restored, emptyList())
        snapshot.plan shouldBe plan
        snapshot.plannedBuildings shouldBe emptyList()
        val complete = adopted.copy(buildings = adopted.buildings + next.building.copy(phase = BuildingPhase.ACTIVE))
        CivsSettlementPlans.next(complete, BuildingType.BUILDER_HUT) shouldBe null
        complete.autonomy?.plan shouldBe plan
    }

    test("an accepted seventy two block plan retains its width plots routes and perimeter under the larger new town bound") {
        val city = settlementPlanCity().copy(radius = 128, autonomy = CivAutonomy(layoutHalfWidth = 72))
        val before = Gson().toJson(city)
        val plan = planWithDiagnostics {
            CivsSettlementPlans.prepare(city, settlementPlanTemplates(), settlementPlanSurface(city.copy(radius = 127)), CivAutonomousAccess())
        }
        plan.halfWidth shouldBe 72
        plan.plots.size shouldBe 34
        for (journalWidth in listOf(72, null)) {
            val adopted = city.copy(autonomy = city.autonomy!!.copy(plan = plan, layoutHalfWidth = journalWidth))
            val restored = CivsRules.validate(Gson().fromJson(Gson().toJson(adopted), Settlement::class.java))
            val restoredPlan = requireNotNull(CivsSettlementPlans.current(restored))
            restoredPlan shouldBe plan
            val frame = CivsSettlementLayout.frame(restored)
            frame.halfWidth shouldBe 72
            frame.wallObstacles shouldBe plan.walls
            frame.gates.map { it.passage } shouldBe plan.gates
            restoredPlan.plots shouldBe plan.plots
            restoredPlan.routes shouldBe plan.routes
            restored.buildings shouldBe city.buildings
            restored.workers shouldBe city.workers
            restored.stock shouldBe city.stock
        }
        Gson().toJson(city) shouldBe before
    }

    test("the full planner extends shared street choices with every new reserved plot") {
        val city = settlementPlanCity()
        val searches = mutableListOf<CivRouteSearchStats>()
        val plan = planWithDiagnostics { CivsSettlementPlans.prepare(city, settlementPlanTemplates(), settlementPlanSurface(city),
            CivAutonomousAccess(), inspectRoute = searches::add) }
        val acceptedJoins = searches.filter { it.outcome == "found" && it.from != city.buildings.single().entrance }
        check(acceptedJoins.isNotEmpty()) { "Later plots never searched a short shared-street connector" }
        val routePoints = plan.routes.values.flatten().toSet()
        acceptedJoins.all { it.from in routePoints } shouldBe true
        plan.plots.filter { it.building.type in CivsSettlementLayout.exteriorTypes }.all {
            !CivsAutonomousGeometry.overlaps(it.envelope, CivsSettlementLayout.frame(city).core)
        } shouldBe true
        city.autonomy?.plan shouldBe null
        city.stock shouldBe Stock()
    }

    test("obsolete unpaid intent is ignored without rejecting existing city production or accepted work") {
        val city = settlementPlanCity()
        val plan = planWithDiagnostics { CivsSettlementPlans.prepare(city, settlementPlanTemplates(), settlementPlanSurface(city), CivAutonomousAccess()) }
        val old = city.copy(autonomy = city.autonomy!!.copy(plan = plan.copy(version = 99)))
        CivsSettlementPlans.current(old) shouldBe null
        CivsRules.validate(old) shouldBe old
        CivsAutonomy.nextGoal(old)?.type shouldBe BuildingType.BUILDER_HUT
    }

    test("an existing gate keeps its accepted transit grade when its lintel covers the captured surface") {
        val city = settlementPlanCity().copy(autonomy = CivAutonomy(layoutHalfWidth = 56))
        val surface = settlementPlanSurface(city)
        // Legacy accepted gates and their original frame, before any adopted full-plan state existed.
        val frame = CivsSettlementLayout.frame(city)
        val gates = CivsSettlementLayout.infrastructure(city, frame, surface, emptyList()).pieces
            .filter { it.type == BuildingType.GATE }
        gates.size shouldBe 3
        val built = city.copy(buildings = city.buildings + gates.map { CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE) },
            autonomy = city.autonomy!!.copy(layoutHalfWidth = frame.halfWidth))
        val occupiedSurface = surface.copy(columns = surface.columns.toMutableMap().apply {
            gates.flatMap { it.blocks }.forEach { block ->
                put(civsTerrainKey(block.position.x, block.position.z), CivTerrainSurfaceColumn(70, false))
            }
        })
        val refreshed = planWithDiagnostics { CivsSettlementPlans.prepare(built, settlementPlanTemplates(), occupiedSurface,
            CivAutonomousAccess(infrastructure = gates)) }
        refreshed.plots.size shouldBe 34
        refreshed.gates shouldBe frame.gates.map { it.passage }
        frame.gates.forEach { gate ->
            refreshed.routes.values.any { it.last() == gate.center.copy(y = 65) } shouldBe true
        }
    }

    test("private real terrain replay adopts the complete profile and a closed perimeter") {
        val path = System.getenv("CIVS_PLAN_BOARD_SCENARIO") ?: return@test
        val (_, snapshot) = readPlanBoardScenario(path)
        snapshot.plan!!.plots.size shouldBe 34
        (snapshot.plan.gates.size in 1..8) shouldBe true
        snapshot.plan.walls.isNotEmpty() shouldBe true
    }
})

private inline fun planWithDiagnostics(prepare: () -> CivSettlementPlan): CivSettlementPlan = try { prepare() }
    catch (failure: CivsRejection) { throw AssertionError("${failure.reason}: ${failure.values}", failure) }

internal fun settlementPlanCity(): Settlement {
    val hall = CivBuilding(UUID(94, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture/hall",
        CivBox(CivPosition(-4, 64, -8), CivPosition(4, 76, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE)
    return Settlement(UUID(94, 2), UUID(94, 3), "Full plan", UUID(94, 4), CivPosition(0, 64, 0), 96,
        "fixture", Stock(), listOf(hall), emptyList(), autonomy = CivAutonomy())
}

internal fun settlementPlanTemplates(): Map<BuildingType, Pair<CivBuilding, CivBuilding>> = CivsAutonomy.profile.associateWith { type ->
    val template = CivBuilding(UUID(94, type.ordinal.toLong() + 100), type, 1, CivPosition(0, 0, 0), 0, "fixture/$type",
        CivBox(CivPosition(-3, 0, -7), CivPosition(3, 7, -1)), CivPosition(0, 1, 0), BuildingPhase.CONSTRUCTING)
    template to template.copy(level = type.maxLevel, bounds = CivBox(CivPosition(-4, 0, -9), CivPosition(4, 12, -1)))
}

internal fun settlementPlanSurface(city: Settlement): CivTerrainSurface = CivTerrainSurface(-64, 320, buildMap {
    for (x in city.center.x - city.radius..city.center.x + city.radius)
        for (z in city.center.z - city.radius..city.center.z + city.radius)
            put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, true))
})

/** Private, source-captured inputs are intentionally external to the public repository. */
internal fun readPlanBoardScenario(path: String): Pair<Settlement, CivPlanBoardSnapshot> {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val document = JsonParser.parseString(Files.readString(Path.of(path))).asJsonObject
    val city = gson.fromJson(document["city"], Settlement::class.java)
    val snapshot = gson.fromJson(document["snapshot"], CivPlanBoardSnapshot::class.java)
    val sources = document.getAsJsonObject("templates")
    val templates = CivsAutonomy.profile.associateWith { type ->
        gson.fromJson(sources["$type-1"], CivBuilding::class.java) to
            gson.fromJson(sources["$type-${type.maxLevel}"], CivBuilding::class.java)
    }
    val surface = gson.fromJson(document["surface"], CivTerrainSurface::class.java)
    val output = System.getenv("CIVS_PLAN_BOARD_PREVIEW_OUT")?.let(Path::of)
    val searches = mutableListOf<CivRouteSearchStats>()
    val started = System.nanoTime()
    val plan = try {
        CivsSettlementPlans.prepare(city, templates, surface, CivAutonomousAccess(snapshot.routes),
            inspectRoute = { searches += it }) { candidate, infrastructure ->
            output?.let {
                Files.createDirectories(it)
                Files.writeString(it.resolve("candidate-infrastructure.json"), gson.toJson(mapOf(
                    "evidence" to "Unadopted candidate diagnostic; acceptance remains dependent on the complete infrastructure check.",
                    "plan" to candidate, "infrastructure" to infrastructure)))
                val unpaid = infrastructure.pieces.filter { city.building(it.id) == null }
                val cost = unpaid.fold(Stock()) { total, piece -> CivsAutonomy.cost(piece.type, 1).let {
                    Stock(total.wood + it.wood, total.stone + it.stone, total.food + it.food)
                } }
                Files.writeString(it.resolve("infrastructure-costs.json"), gson.toJson(mapOf(
                    "evidence" to "Candidate generated order costs; this replay commits no city state or payment.",
                    "pieceCounts" to unpaid.groupingBy { piece -> piece.type }.eachCount(),
                    "unitCosts" to listOf(BuildingType.ROAD, BuildingType.WALL, BuildingType.GATE).associateWith { type -> CivsAutonomy.cost(type, 1) },
                    "totalCost" to cost, "acceptedPiecesUnchanged" to infrastructure.pieces.count { piece -> city.building(piece.id) != null },
                    "perimeterClosed" to infrastructure.perimeterClosed)))
            }
        }
    } catch (failure: Exception) {
        output?.let {
            Files.createDirectories(it)
            Files.writeString(it.resolve("plan-feasibility.json"), gson.toJson(mapOf("success" to false,
                "reason" to failure.message, "details" to (failure as? CivsRejection)?.values)))
        }
        throw failure
    } finally {
        output?.let {
            Files.createDirectories(it)
            Files.writeString(it.resolve("plan-search-metrics.json"), gson.toJson(mapOf(
                "evidence" to "Measured detached planner replay; no world mutation or game-thread work.",
                "prepareMillis" to (System.nanoTime() - started) / 1_000_000,
                "maxExpandedStatesPerSearch" to CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES,
                "searches" to searches)))
        }
    }
    searches.all { it.expanded <= CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES &&
        it.enqueued >= it.expanded && it.discovered <= it.enqueued && it.peakQueue <= it.enqueued } shouldBe true
    output?.let {
        Files.createDirectories(it)
        Files.writeString(it.resolve("plan-feasibility.json"), gson.toJson(mapOf("success" to true, "plan" to plan)))
    }
    return city.copy(autonomy = city.autonomy!!.copy(plan = plan)) to snapshot.copy(plan = plan)
}
