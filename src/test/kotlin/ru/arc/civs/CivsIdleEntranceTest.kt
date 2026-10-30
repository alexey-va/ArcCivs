package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsIdleEntranceTest : FunSpec({
    test("a long house keeps its only exit inside the leisure route domain") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("idle-long-house").also(paper.server::addWorld)
            for (x in -5..-2) for (z in -5..-2) world.loadChunk(x, z)
            for (x in -66..-38) for (z in -64..-43) world.getBlockAt(x, 70, z).type = Material.COBBLESTONE
            // The real Test hut has this twelve-block control-to-entrance offset. This
            // geometric fixture isolates the leisure boundary with a single authored exit.
            val hut = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1,
                CivPosition(-56, 69, -54), 90, "civs/minecolonies/medievaloak/fundamentals/builder1",
                CivBox(CivPosition(-55, 70, -57), CivPosition(-42, 75, -47)),
                CivPosition(-56, 71, -54), BuildingPhase.ACTIVE, CivPosition(-44, 71, -53))
            for (x in -55..-42) for (z in -57..-47) if (x in listOf(-55, -42) || z in listOf(-57, -47)) {
                if (x == -55 && z == -54) continue
                for (y in 71..73) world.getBlockAt(x, y, z).type = Material.COBBLESTONE
            }
            val control = requireNotNull(hut.control)
            world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Long house", world.uid,
                CivPosition(-50, 71, -53), 96, "medievaloak", Stock(), listOf(hut), emptyList())
            val origin = CitizenPoint(-49.5, 71.0, -52.5)
            fun route(anchor: CivPosition, target: CivPosition): CitizenPacketRoute = CitizenPacketRoute(world,
                Location(world, origin.x, origin.y, origin.z),
                Location(world, target.x + .5, target.y.toDouble(), target.z + .5),
                { point -> citizenRoutePointAllowed(city, setOf(hut.id), point) && citizenControlClear(city, point) &&
                    citizenIdleRouteAllowed(anchor, origin, point) && citizenIdleTerrain(world, point, city.buildings) },
                { from, to -> citizenControlStepClear(city, from, to) }, { 1.0 }).also { motion ->
                var operations = 0
                while (motion.searching && operations++ < CitizenNavigationCapture.MAXIMUM_BLOCKS +
                    CitizenNavigationSearch.MAXIMUM_NODES * 64) motion.expand()
                withClue("nodes=${motion.nodeCount} captured=${motion.capturedBlocks} failure=${motion.failure}") {
                    motion.searching shouldBe false
                }
            }
            // A valid exterior target six blocks from the old barrel needs the excluded door.
            // A native no-path prefix can approach the wall, but cannot turn its end into arrival outside.
            val oldTarget = control.copy(z = control.z - 6)
            val blocked = route(control, oldTarget)
            blocked.failure shouldBe null
            blocked.continuation shouldBe true
            blocked.continuationReason shouldBe "no-path"
            val frontier = requireNotNull(blocked.endpoint)
            (frontier == oldTarget.point()) shouldBe false
            var oldAt = Location(world, origin.x, origin.y, origin.z)
            repeat(600) { blocked.move(oldAt, 1.0) { true }?.let { next ->
                citizenPacketSupported(world, next.point()) shouldBe true
                citizenPacketBodyClear(world, next.point(), false) shouldBe true
                citizenPacketStepClear(world, oldAt.point(), next.point(), false) shouldBe true
                oldAt = next
            } }
            blocked.finished shouldBe true
            blocked.failure shouldBe null
            oldAt.point() shouldBe frontier
            citizenArrived(oldAt, Location(world, oldTarget.x + .5, oldTarget.y.toDouble(), oldTarget.z + .5)) shouldBe false
            val anchor = citizenIdleAnchor(city, hut)
            anchor shouldBe hut.entrance
            citizenIdleAnchor(city.copy(autonomy = CivAutonomy()), hut) shouldBe anchor
            val target = anchor.copy(x = anchor.x - 6)
            val motion = route(anchor, target)
            motion.failure shouldBe null
            motion.continuation shouldBe false
            motion.continuationReason shouldBe null
            motion.endpoint shouldBe target.point()
            var at = Location(world, origin.x, origin.y, origin.z)
            repeat(600) { motion.move(at, 1.0) { true }?.let { at = it } }
            motion.finished shouldBe true
            motion.failure shouldBe null
            at.x shouldBe target.x + .5
            at.z shouldBe target.z + .5
            citizenArrived(at, Location(world, target.x + .5, target.y.toDouble(), target.z + .5)) shouldBe true
            citizenPacketBodyClear(world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
        }
    }
})
