package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsAutonomousRouteGuideTest : FunSpec({
    test("a bent ninety eight cell valley proves its complete native route within the original budgets") {
        routeGuideScenario { s ->
            val terrain = s.captureTerrace()
            terrain.route shouldBe s.authored
            terrain.route.size shouldBe 98
            terrain.route.minOf { it.y } shouldBe 61
            (terrain.route.minOf { it.y } < minOf(terrain.route.first().y, terrain.route.last().y) - 2) shouldBe true
            val before = s.snapshot(terrain)
            val cityBefore = s.city
            val proof = s.subject.autonomousRoute(s.city, s.building, terrain.route.first(), s.executor, terrain)
            s.completed(proof) shouldBe Unit
            s.snapshot(terrain) shouldBe before
            s.cities.single() shouldBe cityBefore
        }
    }

    test("an authored guide cannot approve newly blocked native headroom") {
        routeGuideScenario { s ->
            val terrain = s.captureTerrace()
            val head = terrain.route.last().copy(y = terrain.route.last().y + 1)
            terrain.blocks.none { it.position == head } shouldBe true
            s.world.getBlockAt(head.x, head.y, head.z).type = Material.STONE
            val before = s.snapshot(terrain)
            val failure = s.rejected(s.subject.autonomousRoute(s.city, s.building, terrain.route.first(), s.executor, terrain))
            failure.reason shouldBe "autonomy-no-path"
            failure.values["x"] shouldBe "${head.x}"
            failure.values["y"] shouldBe "${head.y}"
            failure.values["z"] shouldBe "${head.z}"
            s.snapshot(terrain) shouldBe before
        }
    }

    test("an authored guide never authorizes replacing native protected ore in its terrace") {
        routeGuideScenario { s ->
            val terrain = s.captureTerrace()
            val cut = terrain.blocks.first { it.before == "minecraft:stone" && it.after == "minecraft:air" }
            s.world.getBlockAt(cut.position.x, cut.position.y, cut.position.z).type = Material.COAL_ORE
            val before = s.snapshot(terrain)
            val failure = s.rejected(s.subject.autonomousRoute(s.city, s.building, terrain.route.first(), s.executor, terrain))
            failure.reason shouldBe "autonomy-terrain"
            failure.values["block"] shouldBe "COAL_ORE"
            s.snapshot(terrain) shouldBe before
        }
    }

    test("an authored guide cannot turn a protected control endpoint into arrival") {
        routeGuideScenario { s ->
            val terrain = s.captureTerrace()
            // A retained SITE control reservation stays furniture even before its barrel is installed.
            val protected = s.city.copy(buildings = listOf(s.city.buildings.single().copy(control = s.building.entrance)))
            s.cities[0] = protected
            citizenControlStepClear(protected, terrain.route[terrain.route.lastIndex - 1].point(), s.building.entrance.point()) shouldBe false
            val before = s.snapshot(terrain)
            val failure = s.rejected(s.subject.autonomousRoute(protected, s.building, terrain.route.first(), s.executor, terrain))
            (failure.reason in setOf("autonomy-no-path", "autonomy-search-budget")) shouldBe true
            failure.values["corridor"] shouldBe "true"
            s.snapshot(terrain) shouldBe before
            s.cities.single() shouldBe protected
        }
    }

    test("a native route without a terrain guide keeps ordinary admission") {
        routeGuideScenario { s ->
            val target = s.building.copy(anchor = CivPosition(7, 63, 0),
                bounds = CivBox(CivPosition(7, 64, -1), CivPosition(9, 72, 1)), entrance = CivPosition(6, 64, 0))
            val before = s.world.getBlockAt(6, 63, 0).blockData.asString
            s.completed(s.subject.autonomousRoute(s.city, target, s.authored.first(), s.executor)) shouldBe Unit
            s.world.getBlockAt(6, 63, 0).blockData.asString shouldBe before
        }
    }
})

/** Native MockBukkit collision/terrain capture, not an observed live-world replay or a mocked path result. */
private class RouteGuideScenario(paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("autonomous-route-guide").also(paper.server::addWorld)
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val executor = Executor(Runnable::run)
    private val hall = CivBuilding(UUID(167, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0,
        "fixture/hall", CivBox(CivPosition(-4, 63, -8), CivPosition(4, 79, -1)), CivPosition(0, 64, 0), BuildingPhase.SITE)
    val city = Settlement(UUID(167, 2), UUID(167, 3), "Route guide", world.uid, CivPosition(0, 64, 0), 128,
        "fixture", Stock(), listOf(hall), emptyList(), autonomy = CivAutonomy())
    val cities = mutableListOf(city)
    val subject = CivsWorld(paper.createSimplePlugin("AutonomousRouteGuideTest"), tasks, { 0L }) { cities }
    val authored = buildList {
        add(hall.entrance)
        add(CivPosition(-1, 64, 0)); add(CivPosition(-2, 63, 0)); add(CivPosition(-3, 62, 0)); add(CivPosition(-4, 61, 0))
        for (z in 1..49) add(CivPosition(-4, 61, z))
        // Reach the landing at x36, before the mandatory flat apron at x37..40.
        for (x in -3..40) add(CivPosition(x, 61 + (x - 26).coerceIn(0, 10), 49))
    }
    val building = CivBuilding(UUID(167, 4), BuildingType.BUILDER_HUT, 1, CivPosition(40, 70, 49), 90,
        "fixture/hut", CivBox(CivPosition(41, 66, 45), CivPosition(47, 82, 53)), authored.last(), BuildingPhase.SITE)

    init {
        for (cx in -7..6) for (cz in -7..6) world.loadChunk(cx, cz)
        // The lower corridor is cut into the native stone plane. These natural banks prevent
        // an unrelated plateau shortcut from joining the raised final ramp from the side.
        for (x in 26..40) for (z in listOf(47, 51)) for (y in 64..74)
            world.getBlockAt(x, y, z).type = Material.STONE
        authored.size shouldBe 98
    }

    fun captureTerrace(): CivTerraformPlan {
        val surface = CivTerrainSurface(world.minHeight, world.maxHeight, buildMap {
            for (x in -127..127) for (z in -127..127) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(63, true))
        })
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface, reservedRoute = authored)
        return completed(subject.captureTerraform(city, draft, executor))
    }

    fun snapshot(terrain: CivTerraformPlan): Map<CivPosition, String> = buildMap {
        val positions = terrain.blocks.map { it.position } + terrain.route.flatMap { feet -> (-1..1).map { feet.copy(y = feet.y + it) } }
        positions.forEach { p -> put(p, world.getBlockAt(p.x, p.y, p.z).blockData.asString) }
    }

    private fun await(future: CompletableFuture<*>) {
        repeat(1_000) { if (!future.isDone) scheduler.tick() }
        future.isDone shouldBe true
    }
    fun <T> completed(future: CompletableFuture<T>): T {
        await(future)
        return try { future.join() } catch (failure: RuntimeException) {
            val rejection = CivsDiagnostics.rejection(failure)
            throw AssertionError("World preflight failed: reason=${rejection?.reason}, details=${rejection?.values}", failure)
        }
    }
    fun rejected(future: CompletableFuture<*>): CivsRejection {
        await(future)
        future.isCompletedExceptionally shouldBe true
        return requireNotNull(CivsDiagnostics.rejection(requireNotNull(runCatching { future.join() }.exceptionOrNull()))).also {
            println("WORLD_ROUTE_TEST_REJECTION reason=${it.reason}, details=${it.values}")
        }
    }
    override fun close() { subject.close(); tasks.close() }
}

private fun routeGuideScenario(action: (RouteGuideScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> RouteGuideScenario(paper).use(action) }
}
