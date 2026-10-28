package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.type.Door
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsLongDoorApproachTest : FunSpec({
    test("a clear long diagonal can approach an already open door without failing early") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("long-door", Material.STONE) { data ->
                if (data.material != Material.SPRUCE_DOOR) null else listOf(
                    if ((data as Door).isOpen) BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0)
                    else BoundingBox(0.0, 0.0, 0.0, 3.0 / 16.0, 1.0, 1.0))
            }.also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for ((y, half) in listOf(64 to "lower", 65 to "upper")) {
                world.getBlockAt(4, y, 2).blockData = Bukkit.createBlockData(
                    "minecraft:spruce_door[facing=east,half=$half,hinge=right,open=true,powered=false]")
            }
            val city = Settlement(UUID(0, 11), UUID(0, 12), "Door test", world.uid,
                CivPosition(0, 64, 0), 16, "fixture", Stock(), emptyList(), emptyList())
            var at = Location(world, .5, 64.0, .5)
            val target = Location(world, 4.5, 64.0, 2.5)
            // An open door still obeys world locks and protected building footprints.
            citizenOpenRouteDoors(city, emptySet(), at, target) { _, _ -> true } shouldBe false
            val protected = CivBuilding(UUID(0, 10), BuildingType.HOUSE, 1, CivPosition(4, 63, 2), 0,
                "scenario/protected-door", CivBox(CivPosition(4, 63, 2), CivPosition(4, 66, 2)),
                CivPosition(4, 64, 2), BuildingPhase.ACTIVE)
            citizenOpenRouteDoors(city.copy(buildings = listOf(protected)), emptySet(), at, target) { _, _ -> false } shouldBe false
            val route = CitizenPacketRoute(world, at, target,
                { p -> p.x in 0..4 && p.z in 0..2 && p.y == 64 && citizenRoutePointAllowed(city, emptySet(), p) },
                { from, to -> citizenControlStepClear(city, from, to) }, { 1.0 })
            repeat(10_000) { if (route.searching) route.expand() }
            route.searching shouldBe false
            route.failure shouldBe null
            repeat(300) {
                route.move(at, .8) { destination ->
                    citizenOpenRouteDoors(city, emptySet(), at, destination) { _, _ -> false }.also {
                        if (!it) println("denied at=$at waypoint=$destination distance=${at.distance(destination)} doorOpen=${(world.getBlockAt(4, 64, 2).blockData as Door).isOpen}")
                    }
                }?.let { at = it }
                route.failure shouldBe null
            }
            route.finished shouldBe true
            (at.distanceSquared(target) < .000001) shouldBe true
        }
    }
})
