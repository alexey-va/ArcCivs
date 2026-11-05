package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

/** Public flat geometry: real draft/capture guards, no imported schematic or native export. */
class CivsWallRoadShoulderTest : FunSpec({
    test("a three-column wall admits its exact paid road shoulder without preserving the wall column") {
        wallShoulderScenario { s ->
            val natural = s.draft()
            s.wallColumn(natural).preserve shouldBe false
            val control = s.capture(natural)
            control.building shouldBe s.wall
            s.assertPaidRoadUnchanged(control)

            s.path()
            s.assertOwnedShoulder()
            val proposed = s.generate(s.surface(path = true))
            proposed.rejected shouldBe emptyMap()
            proposed.perimeterClosed shouldBe true
            CivsInfrastructure.building(proposed.pieces.single { it.type == BuildingType.WALL }) shouldBe s.wall
            // The same footprint, source, route and native world differ at this one floor only.
            // Expected RED on the old draft guard, after the complete natural-control capture.
            val draft = s.draft(path = true)
            draft.route shouldBe natural.route
            s.wallColumn(draft).groundY shouldBe s.shoulder.y
            s.wallColumn(draft).floorY shouldBe s.shoulder.y
            s.wallColumn(draft).preserve shouldBe false
            val captured = s.capture(draft)
            captured.blocks.single { it.position == s.shoulder }.before shouldBe "minecraft:dirt_path"
            s.assertPaidRoadUnchanged(captured)
            s.block().type shouldBe Material.DIRT_PATH // Capture proposes paid work; it does not place it.
        }
    }

    test("an unvalidated road journal cannot authorize a wall over a dirt path") {
        wallShoulderScenario { s ->
            s.path()
            val invalid = CivAutonomousAccess.from(s.city, listOf(s.paid.copy(cityId = UUID(218, 99))))
            invalid.routes shouldBe emptyMap()
            shouldThrow<CivsRejection> { s.draft(path = true, access = invalid) }.reason shouldBe "autonomy-terrain"
        }
    }

    test("paid shoulder context does not admit an ordinary building on the path") {
        wallShoulderScenario { s ->
            s.path()
            s.assertOwnedShoulder()
            val ordinary = s.wall.copy(type = BuildingType.HOUSE, schematicId = "fixture/shoulder-house")
            shouldThrow<CivsRejection> { s.draft(path = true, building = ordinary) }.reason shouldBe "autonomy-terrain"
        }
    }

    for (offset in listOf(-1, 1))
    test("a wall cannot claim a paid road shoulder whose exact floor differs by $offset") {
        wallShoulderScenario { s ->
            s.path()
            val route = s.paidRoute.map { if (it.x == 4 && it.z == 9) it.copy(y = it.y + offset) else it }
            val plan = s.paid.copy(terrain = requireNotNull(s.paid.terrain).copy(route = route))
            val access = CivAutonomousAccess.from(s.city, listOf(plan))
            access.routes.getValue(s.forester.id) shouldBe route
            CivsAutonomousTerraform.roadProfile(route)[civsTerrainKey(s.shoulder.x, s.shoulder.z)] shouldBe
                s.shoulder.copy(y = s.shoulder.y + offset)
            CivsAutonomousTerraform.roadProfileContains(route, s.shoulder) shouldBe false
            access.standing(s.city, s.wall.id).containsKey(civsTerrainKey(s.shoulder.x, s.shoulder.z)) shouldBe false
            shouldThrow<CivsRejection> { s.draft(path = true, access = access) }.reason shouldBe "autonomy-terrain"
        }
    }

    test("a wall cannot claim dirt path where its exact paid profile requires stairs") {
        wallShoulderScenario { s ->
            s.path()
            val route = s.paidRoute.map { if (it.x == 3 && it.z == 9) it.copy(y = it.y - 1) else it }
            val plan = s.paid.copy(terrain = requireNotNull(s.paid.terrain).copy(route = route))
            val access = CivAutonomousAccess.from(s.city, listOf(plan))
            access.routes.getValue(s.forester.id) shouldBe route
            CivsAutonomousTerraform.roadProfileContains(route, s.shoulder) shouldBe true
            val profile = CivsAutonomousTerraform.roadProfile(route).mapValues { it.value.y }
            CivsSettlementLayout.pathPavement(s.shoulder, profile, s.city.style) shouldBe
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
            access.standing(s.city, s.wall.id).containsKey(civsTerrainKey(s.shoulder.x, s.shoulder.z)) shouldBe false
            shouldThrow<CivsRejection> { s.draft(path = true, access = access) }.reason shouldBe "autonomy-terrain"
        }
    }

    test("a retained infrastructure route cannot authorize an ordinary paid road shoulder") {
        wallShoulderScenario { s ->
            s.path()
            val road = s.forester.copy(type = BuildingType.ROAD, schematicId = "fixture/paid-infrastructure-road")
            s.cities[0] = s.city.copy(buildings = s.city.buildings.map { if (it.id == road.id) road else it })
            val access = CivAutonomousAccess.from(s.city, listOf(s.paid.copy(building = road)))
            access.routes.getValue(road.id) shouldBe s.paidRoute
            CivsAutonomousTerraform.roadProfileContains(access.routes.getValue(road.id), s.shoulder) shouldBe true
            val profile = CivsAutonomousTerraform.roadProfile(s.paidRoute).mapValues { it.value.y }
            CivsSettlementLayout.pathPavement(s.shoulder, profile, s.city.style) shouldBe "minecraft:dirt_path"
            access.standing(s.city, s.wall.id).containsKey(civsTerrainKey(s.shoulder.x, s.shoulder.z)) shouldBe false
            shouldThrow<CivsRejection> { s.draft(path = true, access = access) }.reason shouldBe "autonomy-terrain"
        }
    }

    test("the infrastructure generator rejects a wall crossing the actual paid centreline") {
        wallShoulderScenario { s ->
            val crossing = s.footprint.copy(min = s.footprint.min.copy(z = 7), max = s.footprint.max.copy(z = 9))
            val generated = s.generate(s.surface(), crossing)
            (generated.rejected.getOrDefault("route-crosses-wall", 0) > 0) shouldBe true
            generated.perimeterClosed shouldBe false
            generated.pieces.filter { it.type == BuildingType.WALL }.none {
                CivsAutonomousGeometry.horizontalContains(it.bounds, CivPosition(4, 63, 9))
            } shouldBe true
            s.access.routes.getValue(s.forester.id) shouldBe s.paidRoute
        }
    }

    test("a protected native replacement rejects the wall after the natural draft was prepared") {
        wallShoulderScenario { s ->
            val draft = s.draft()
            s.wallColumn(draft).preserve shouldBe false
            s.block().type = Material.BARRIER
            val rejected = s.failure(s.subject.captureTerraform(s.city, draft, Executor(Runnable::run)))
            rejected.reason shouldBe "autonomy-terrain"
            rejected.values["stage"] shouldBe "support"
            rejected.values["x"] shouldBe s.shoulder.x.toString()
            rejected.values["y"] shouldBe s.shoulder.y.toString()
            rejected.values["z"] shouldBe s.shoulder.z.toString()
            s.block().type shouldBe Material.BARRIER
        }
    }

    test("ordinary wall capture retains the native Lands permission check") {
        wallShoulderScenario { s ->
            val draft = s.draft()
            s.path() // Actual path support is read by native capture, not replaced by a mocked response.
            val terrain = s.terrain(draft)
            val lands = s.paper.createSimplePlugin("Lands")
            lands.server.pluginManager.disablePlugin(lands)
            s.subject.canModify(s.city.owner, s.block()) shouldBe false
            s.failure(s.captureFuture(terrain)).reason shouldBe "lands-denied"
            s.block().type shouldBe Material.DIRT_PATH
        }
    }

    test("ordinary wall capture rejects ownership acquired after terrain capture") {
        wallShoulderScenario { s ->
            val draft = s.draft()
            s.path()
            val terrain = s.terrain(draft)
            val occupying = s.forester.copy(id = UUID(218, 8), type = BuildingType.HOUSE,
                schematicId = "fixture/occupied-shoulder", anchor = s.shoulder,
                bounds = CivBox(s.shoulder, s.shoulder.copy(y = 65)), entrance = CivPosition(5, 64, 8))
            s.cities[0] = s.city.copy(buildings = s.city.buildings + occupying)
            s.failure(s.captureFuture(terrain)).reason shouldBe "building-overlap"
            s.block().type shouldBe Material.DIRT_PATH
        }
    }
})

private fun wallShoulderScenario(action: (WallRoadShoulderScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> WallRoadShoulderScenario(paper).use(action) }
}

private class WallRoadShoulderScenario(val paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("wall-road-shoulder").also(paper.server::addWorld)
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    private val hall = CivBuilding(UUID(218, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0, "fixture/hall",
        CivBox(CivPosition(-2, 63, -5), CivPosition(2, 69, -1)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
    val forester = CivBuilding(UUID(218, 2), BuildingType.FORESTER, 1, CivPosition(8, 63, 9), 180, "fixture/forester",
        CivBox(CivPosition(7, 63, 10), CivPosition(9, 69, 12)), CivPosition(8, 64, 9), BuildingPhase.ACTIVE)
    val cities = mutableListOf(Settlement(UUID(218, 3), UUID(218, 4), "Wall road shoulder", world.uid,
        hall.anchor, 32, "medievaloak", Stock(), listOf(hall, forester), emptyList(), autonomy = CivAutonomy()))
    val city: Settlement get() = cities[0]
    val paidRoute = (0..9).map { CivPosition(0, 64, it) } + (1..8).map { CivPosition(it, 64, 9) }
    val paid = CivPlacementPlan(forester.id, city.id, world.uid, forester, emptyList(),
        terrain = CivTerraformPlan(CivBox(CivPosition(0, 63, 0), CivPosition(8, 65, 9)), emptyList(), paidRoute, 63, 0, 0))
    val access = CivAutonomousAccess.from(city, listOf(paid))
    val shoulder = CivPosition(4, 63, 8)
    val footprint = CivBox(CivPosition(4, 63, 6), shoulder)
    private val piece = generate(surface(), footprint).also {
        it.rejected shouldBe emptyMap()
        it.perimeterClosed shouldBe true
    }.pieces.single { it.type == BuildingType.WALL }
    val wall = CivsInfrastructure.building(piece)
    private val floors = piece.blocks.groupBy { civsTerrainKey(it.position.x, it.position.z) }
        .mapValues { (_, cells) -> cells.minOf { it.position.y } }
    private val cells = piece.blocks.map { ArcSchematicBlock(ArcSchematicPoint(it.position.x, it.position.y, it.position.z),
        it.after, it.after == "minecraft:air", it.after == "minecraft:barrier") }
    val subject = CivsWorld(paper.createSimplePlugin("WallRoadShoulderTest"), tasks, { 0L }) { cities }

    init {
        for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
        floors.size shouldBe 3
        cells.size.toLong() shouldBe civsBoxVolume(wall.bounds)
        access.routes.getValue(forester.id) shouldBe paidRoute
    }

    fun surface(path: Boolean = false) = CivTerrainSurface(-64, 320, buildMap {
        for (x in -8..16) for (z in -8..16) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(63, true))
        if (path) put(civsTerrainKey(shoulder.x, shoulder.z), CivTerrainSurfaceColumn(63, false, dirtPath = true))
    })

    fun generate(surface: CivTerrainSurface, box: CivBox = footprint): CivSettlementInfrastructure =
        CivsSettlementLayout.infrastructure(city, CivsSettlementLayout.frame(city, 24).copy(
            wallObstacles = listOf(box), gates = emptyList(), fortified = false), surface,
            listOf(paidRoute), pavingRoutes = emptyList(), retainedRoutes = listOf(paidRoute))

    fun draft(path: Boolean = false, access: CivAutonomousAccess = this.access, building: CivBuilding = wall): CivTerraformDraft {
        val entry = building.entrance
        // A fixed short orthogonal approach isolates pad admission from search tie-breaking.
        val route = (0..entry.x).map { CivPosition(it, entry.y, 0) } +
            (1..entry.z).map { CivPosition(entry.x, entry.y, it) }
        return CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface(path), access,
            additionalObstacles = listOf(footprint), infrastructureFloors = floors, reservedRoute = route)
    }

    fun block() = world.getBlockAt(shoulder.x, shoulder.y, shoulder.z)
    fun path() { block().type = Material.DIRT_PATH }
    fun wallColumn(draft: CivTerraformDraft) = draft.columns.single { it.x == shoulder.x && it.z == shoulder.z }

    fun assertOwnedShoulder() {
        val profile = CivsAutonomousTerraform.roadProfile(paidRoute)
        profile[civsTerrainKey(shoulder.x, shoulder.z)] shouldBe shoulder
        CivsAutonomousTerraform.roadProfileContains(access.routes.getValue(forester.id), shoulder) shouldBe true
        CivsSettlementLayout.pathPavement(shoulder, profile.mapValues { it.value.y }, city.style) shouldBe "minecraft:dirt_path"
        paidRoute.none { it.x == shoulder.x && it.z == shoulder.z } shouldBe true
        access.standing(city, wall.id).containsKey(civsTerrainKey(shoulder.x, shoulder.z)) shouldBe false
        block().collisionShape.boundingBoxes.maxOf { it.maxY } shouldBe 15.0 / 16.0
    }

    fun terrain(draft: CivTerraformDraft): CivTerraformPlan = completed(
        subject.captureTerraform(city, draft, Executor(Runnable::run)))
    fun captureFuture(terrain: CivTerraformPlan): CompletableFuture<CivPlacementPlan> =
        subject.captureAutonomousSite(city.id, city.owner, world.uid, wall, cells, terrain)
    fun capture(draft: CivTerraformDraft): CivPlacementPlan = completed(captureFuture(terrain(draft)))

    fun assertPaidRoadUnchanged(plan: CivPlacementPlan) {
        access.routes.getValue(forester.id) shouldBe paidRoute
        val centreline = paidRoute.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        (plan.blocks + requireNotNull(plan.terrain).blocks).none {
            civsTerrainKey(it.position.x, it.position.z) in centreline
        } shouldBe true
        paidRoute.all { world.getBlockAt(it.x, it.y - 1, it.z).type == Material.STONE } shouldBe true
    }

    private fun drain(future: CompletableFuture<*>) {
        repeat(200) { if (!future.isDone) scheduler.tick() }
        future.isDone shouldBe true
    }
    private fun <T> completed(future: CompletableFuture<T>): T { drain(future); return future.join() }
    fun failure(future: CompletableFuture<*>): CivsRejection {
        drain(future)
        return requireNotNull(CivsDiagnostics.rejection(shouldThrow<CompletionException> { future.join() }))
    }
    override fun close() { subject.close(); tasks.close() }
}
