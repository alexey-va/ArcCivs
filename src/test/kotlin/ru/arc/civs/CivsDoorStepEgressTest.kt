package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsDoorStepEgressTest : FunSpec({
    test("an offset stair exit clears its open door panel before descending diagonally") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            // The stair, side panel and offset are normalized from the native lab capture
            // of 2026-11-04. The remaining floor is an explicit modeled continuation.
            val world = CivsWorksiteWorldFixture("stair-door-egress", ground = Material.AIR, collisionForData = { data ->
                when (data.material) {
                    Material.OAK_STAIRS -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                        BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5))
                    Material.OAK_DOOR -> listOf(BoundingBox(.8125, 0.0, 0.0, 1.0, 1.0, 1.0))
                    else -> null
                }
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for (x in -2..4) for (z in -2..4) world.getBlockAt(x, 62, z).type = Material.GRASS_BLOCK
            world.getBlockAt(0, 63, 0).blockData = Bukkit.createBlockData(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            for (y in 64..65) world.getBlockAt(0, y, -1).blockData = Bukkit.createBlockData(
                "minecraft:oak_door[facing=south,half=${if (y == 64) "lower" else "upper"},hinge=left,open=true,powered=false]")
            val target = Location(world, 2.5, 63.0, 2.5)
            for (speed in listOf(.55, 1.3)) {
                var at = Location(world, .5, 64.0, .134736557857, -45f, 0f)
                val start = at.point()
                val first = CitizenPoint(1.5, 63.0, 1.5)
                citizenPacketBodyClear(world, start, false) shouldBe true
                citizenPacketStepClear(world, start, first, false) shouldBe false
                citizenPacketStepClear(world, CitizenPoint(.5, 64.0, .5), first, false) shouldBe true
                val route = CitizenPacketRoute(world, at, target, { it.y in 63..64 }, { _, _ -> true }, { 1.0 })
                // Exercise the planner/movement handoff directly: the planner permits an
                // openable passage, while movement must respect its current thin panel.
                route.acceptSearch(CitizenSearchResult(emptyList(), 2, path = listOf(
                    CitizenPlannedStep(first, CitizenRouteAction.STEP),
                    CitizenPlannedStep(target.point(), CitizenRouteAction.WALK))))
                repeat(120) { tick ->
                    route.move(at, speed) { true }?.let { next ->
                        citizenPacketBodyClear(world, next.point(), false) shouldBe true
                        (at.distance(next) <= .215 * speed + 1.000001) shouldBe true
                        at = next
                    }
                    withClue("speed=$speed tick=$tick actual=${at.point()}") { route.failure shouldBe null }
                }
                route.finished shouldBe true
                // Packet waypoints finish within their existing one-millimetre tolerance.
                (at.distanceSquared(target) < .000001) shouldBe true
            }
        }
    }
})
