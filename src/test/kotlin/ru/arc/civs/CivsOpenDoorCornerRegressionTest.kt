package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.type.Door
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

/** Portable doorway only: observed Auto2 within-cell offset and pinned Purpur door shapes.
 * No private schematic, resident inventory, hunger/food change, or supplied arrival. */
class CivsOpenDoorCornerRegressionTest : FunSpec({
    for ((name, opened, offset, facing) in listOf(
        NativeDoorApproach("observed offset through an already-open native panel", true, true),
        NativeDoorApproach("centered approach through an already-open native panel", true, false),
        NativeDoorApproach("ordinary opening of a closed native door", false, false),
        NativeDoorApproach("observed offset before ordinary opening of a closed native door", false, true),
        NativeDoorApproach("mirrored offset through an already-open native panel", true, true, "east"),
    )) test(name) {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("native-open-door-$opened-$offset-$facing", Material.STONE) { data ->
                if (data.material != Material.SPRUCE_DOOR) null else listOf(
                    if ((data as Door).isOpen) {
                        if (facing == "west") BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 3.0 / 16.0)
                        else BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0)
                    } else if (facing == "west") BoundingBox(13.0 / 16.0, 0.0, 0.0, 1.0, 1.0, 1.0)
                    else BoundingBox(0.0, 0.0, 0.0, 3.0 / 16.0, 1.0, 1.0))
            }.also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for ((y, half) in listOf(64 to "lower", 65 to "upper")) world.getBlockAt(1, y, 0).blockData =
                Bukkit.createBlockData("minecraft:spruce_door[facing=$facing,half=$half,hinge=right,open=$opened,powered=false]")
            val own = CivBuilding(UUID(0, 10), BuildingType.HOUSE, 1, CivPosition(0, 63, 0), 0,
                "scenario/native-door", CivBox(CivPosition(-1, 63, -1), CivPosition(4, 67, 1)),
                CivPosition(4, 64, 0), BuildingPhase.ACTIVE)
            val city = Settlement(UUID(0, 11), UUID(0, 12), "Native doorway", world.uid,
                CivPosition(0, 64, 0), 16, "fixture", Stock(), listOf(own), emptyList())
            val allowed = setOf(own.id)
            // Translate the recorded builder position into its original grid cell.
            var at = if (offset) Location(world, .6107997686356, 64.0,
                if (facing == "west") .38956481488 else .61043518512)
                else Location(world, .5, 64.0, .5)
            val source = CitizenPoint(at.x, at.y, at.z)
            val center = CitizenPoint(.5, 64.0, .5)
            val door = CitizenPoint(1.5, 64.0, .5)
            citizenPacketSupported(world, source) shouldBe true
            citizenPacketBodyClear(world, source, false) shouldBe true
            if (offset && opened) {
                citizenPacketStepClear(world, source, door, true) shouldBe true
                citizenPacketStepClear(world, source, door, false) shouldBe false
                citizenPacketStepClear(world, source, center, false) shouldBe true
                citizenPacketStepClear(world, center, door, false) shouldBe true
            }
            val target = Location(world, 3.5, 64.0, .5)
            val route = CitizenPacketRoute(world, at, target,
                { p -> p.x in 0..3 && p.z == 0 && p.y == 64 && citizenRoutePointAllowed(city, allowed, p) },
                { from, to -> citizenControlStepClear(city, from, to) }, { 1.0 })
            var preparations = 0
            while (route.searching && preparations++ < 320) route.expand()
            route.searching shouldBe false
            route.failure shouldBe null
            val initialDestination = route.destination
            var walked = 0.0
            repeat(160) { tick ->
                route.move(at, .55) { destination ->
                    citizenOpenRouteDoors(city, allowed, at, destination) { _, _ -> false }
                }?.let { next ->
                    citizenPacketBodyClear(world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    citizenPacketSupported(world, CitizenPoint(next.x, next.y, next.z)) shouldBe true
                    walked += at.distance(next)
                    at = next
                }
                withClue("tick=$tick at=$at initial=$initialDestination walked=$walked nodes=${route.nodeCount}") {
                    route.failure shouldBe null
                }
            }
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
            (walked > 2.0) shouldBe true
            (world.getBlockAt(1, 64, 0).blockData as Door).isOpen shouldBe true
            (world.getBlockAt(1, 65, 0).blockData as Door).isOpen shouldBe true
            println("Native door approach facing=$facing opened=$opened offset=$offset preparations=$preparations initial=$initialDestination walked=$walked")
        }
    }
})

private data class NativeDoorApproach(val name: String, val opened: Boolean, val offset: Boolean, val facing: String = "west")
