package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineExitNavigationTest : FunSpec({
    test("the observed quarry return climbs to its entrance before same-height outdoor storage") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("same-height-quarry-return").also(paper.server::addWorld)
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
                .copy(entrance = CivPosition(-1844, 71, -2696), control = CivPosition(-1844, 71, -2689))
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
            bottom shouldBe CivPosition(-1841, 65, -2686)
            for (x in -117..-114) for (z in -173..-167) world.loadChunk(x, z)
            // Native terrain captured on 2026-10-26: the western-facing ladder runs
            // from y63 through y67. This fixture verifies the first leg, not the uncaptured stairs.
            for (y in 63..67) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=west,waterlogged=false]")
            world.getBlockAt(mine.entrance.x, mine.entrance.y - 1, mine.entrance.z).type = Material.STONE
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                center = mine.anchor, minePlans = mapOf(mine.id.toString() to CivMinePlanState(5, 19)))
            val storage = CivPosition(-1868, 64, -2759)
            var at = Location(world, -1840.844, 64.0, -2685.5)
            fun location(point: CivPosition) = Location(world, point.x + .5, point.y.toDouble(), point.z + .5)

            citizenMineWaypoint(city, world, at, storage) shouldBe bottom
            val route = CitizenPacketRoute(world, at, location(bottom), { true }, { _, _ -> true }, { 1.0 })
            var operations = 0
            while (route.searching && operations++ < 10_000) route.expand()
            withClue("nodes=${route.nodeCount} captured=${route.capturedBlocks}") {
                route.searching shouldBe false
                route.failure shouldBe null
            }
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    (at.distance(next) <= .215001) shouldBe true
                    citizenPacketBodyClear(world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    at = next
                }
            }
            route.finished shouldBe true
            at.distanceSquared(location(bottom)) shouldBe 0.0
            citizenMineWaypoint(city, world, at, storage) shouldBe mine.entrance
            citizenMineWaypoint(city, world, location(mine.entrance), storage) shouldBe null
        }
    }

    test("a same-floor underground destination does not acquire an outdoor exit waypoint") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("same-floor-mine-work").also(paper.server::addWorld)
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 80, 0), 180)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val floor = geometry.floors.first()
            val points = floor.cells.map { CivsWorksiteLayouts.point(mine, it.copy(y = floor.feetY)) }
                .filter { CivsMinePlan.workStandingAllowed(mine, it) }
            (points.size > 1) shouldBe true
            val from = points.first()
            val target = points.last()
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
            citizenMineWaypoint(city, world, Location(world, from.x + .5, from.y.toDouble(), from.z + .5), target) shouldBe null
        }
    }
})
