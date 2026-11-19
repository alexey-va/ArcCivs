package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsPacketTraversalTest : FunSpec({
    test("a wall grapple casts on supported feet then pulls and mantles, including the reverse descent") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("native-wall-grapple").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            for (y in 64..67) world.getBlockAt(1, y, 0).type = Material.STONE
            val bottom = Location(world, .5, 64.0, .5, -90f, 0f)
            val cap = Location(world, 1.5, 68.0, .5, -90f, 0f)
            for ((from, to) in listOf(bottom to cap, cap to bottom)) {
                val route = traversalRoute(world, from, listOf(CitizenPlannedStep(to.point(), CitizenRouteAction.GRAPPLE)))
                var at = from
                var ticks = 0
                while (!route.finished && route.failure == null && ticks < 200) {
                    val next = requireNotNull(route.move(at, 4.0) { error("grapple tried to open a door") })
                    ticks++
                    if (ticks <= CITIZEN_GRAPPLE_CAST_TICKS) next.point() shouldBe from.point()
                    (at.distance(next) <= 1.4 * CITIZEN_GRAPPLE_STRIDE + .000001) shouldBe true
                    if (next.y < cap.y) next.x shouldBe bottom.x
                    citizenPacketBodyClear(world, next.point(), false) shouldBe true
                    at = next
                }
                withClue("from=${from.point()} to=${to.point()} frames=$ticks") {
                    at.point() shouldBe to.point()
                    (ticks in 150..185) shouldBe true
                    route.failure shouldBe null
                    route.grapple shouldBe null
                    route.finished shouldBe true
                    (route.traversalMillis >= ticks * 50L) shouldBe true
                }
            }
            val interrupted = traversalRoute(world, bottom, listOf(CitizenPlannedStep(cap.point(), CitizenRouteAction.GRAPPLE)))
            var at = bottom
            repeat(60) { at = requireNotNull(interrupted.move(at, 1.0) { true }) }
            (at.y in 65.0..67.0) shouldBe true
            world.getBlockAt(1, 67, 0).type = Material.AIR
            interrupted.move(at, 1.0) { true } shouldBe null
            interrupted.failure shouldBe "path-changed"
            interrupted.grapple shouldBe null
        }
    }

    test("a settlement-height wall with a lower slab cap lands on its native fractional floor") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("native-tall-cap").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val from = Location(world, 4.5, 64.0, 5.5, 180f, 0f)
            for (y in 64..70) world.getBlockAt(4, y, 4).type = Material.STONE
            world.getBlockAt(4, 71, 4).type = Material.STONE_SLAB
            val ledge = CitizenPoint(4.5, 71.5, 4.5)
            val route = traversalRoute(world, from, listOf(CitizenPlannedStep(ledge, CitizenRouteAction.GRAPPLE)))
            var at = from
            var ticks = 0
            while (route.navigating && ticks++ < 350) route.move(at, .8) { true }?.let { at = it }
            route.failure shouldBe null
            route.finished shouldBe true
            at.point() shouldBe ledge
            citizenPacketSupported(world, ledge, allowClimbable = false) shouldBe true
        }
    }

    test("a crawl crosses the actual one-block opening, stops at a missing floor and resumes from low offset feet") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("native-low-crawl").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            for (x in 1..3) world.getBlockAt(x, 65, 0).type = Material.STONE
            val from = Location(world, .5, 64.0, .5, -90f, 0f)
            val steps = (1..4).map { CitizenPlannedStep(CitizenPoint(it + .5, 64.0, .5), CitizenRouteAction.CRAWL) }
            var route = traversalRoute(world, from, steps)
            var at = from
            var ticks = 0
            while (!route.finished && route.failure == null && ticks < 100) {
                val next = requireNotNull(route.move(at, 4.0) { error("crawl tried to open a door") })
                ticks++
                (at.distance(next) <= CITIZEN_CRAWL_STRIDE + .000001) shouldBe true
                citizenPacketBodyClear(world, next.point(), false, height = CITIZEN_CRAWL_HEIGHT) shouldBe true
                if (next.blockX in 1..3) citizenPacketBodyClear(world, next.point(), false) shouldBe false
                at = next
            }
            ticks shouldBe 64
            at.point() shouldBe steps.last().point
            route.failure shouldBe null
            route.finished shouldBe true
            route = traversalRoute(world, from, steps)
            at = from
            repeat(19) { at = requireNotNull(route.move(at, 1.0) { true }) }
            val lowFeet = at.point()
            citizenPacketBodyClear(world, lowFeet, false) shouldBe false
            world.getBlockAt(at.blockX, 63, 0).type = Material.AIR
            route.move(at, 1.0) { true } shouldBe null
            route.failure shouldBe "path-changed"
            route.cancel()
            world.getBlockAt(at.blockX, 63, 0).type = Material.STONE
            route = traversalRoute(world, at, steps.drop(1))
            while (route.navigating && ticks++ < 160) route.move(at, 1.0) { true }?.let { at = it }
            route.failure shouldBe null
            at.point() shouldBe steps.last().point
            citizenPacketBodyClear(world, at.point(), false) shouldBe true
        }
    }
})

private fun traversalRoute(world: World, from: Location, steps: List<CitizenPlannedStep>): CitizenPacketRoute {
    val target = steps.last().point
    val policy = CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true }, specialTraversal = true)
    return CitizenPacketRoute(world, from, Location(world, target.x, target.y, target.z),
        { true }, policy.stepAllowed, { 1.0 }, snapshotPolicy = policy).also {
        it.acceptSearch(CitizenSearchResult(emptyList(), steps.size, path = steps))
    }
}
