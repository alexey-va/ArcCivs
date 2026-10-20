package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsResidentWorkReachTest : FunSpec({
    test("reach accepts the actual target hit or unobstructed air but rejects an intervening wall") {
        reachScenario {
            val f = WorkReachFixture()
            val target = CivPosition(2, 64, 0)
            f.hit = f.hitAt(target)
            citizenCanReachBlock(f.npc, target) shouldBe true
            f.hit = f.hitAt(CivPosition(1, 65, 0))
            citizenCanReachBlock(f.npc, target) shouldBe false
            f.hit = null
            citizenCanReachBlock(f.npc, target) shouldBe true
            f.rays shouldBe 3
            f.lastRayStart shouldBe Location(f.world, .5, 65.62, .5)
            (requireNotNull(f.lastRayLength) <= 3.0) shouldBe true
        }
    }

    test("out-of-reach and unloaded targets never trigger a terrain ray or load a chunk") {
        reachScenario {
            val f = WorkReachFixture()
            citizenCanReachBlock(f.npc, CivPosition(4, 64, 0)) shouldBe false
            f.rays shouldBe 0
            f.chunkChecks shouldBe 0
            f.at = Location(f.world, 15.5, 64.0, .5)
            f.unloadedX = 1
            citizenCanReachBlock(f.npc, CivPosition(16, 64, 0)) shouldBe false
            f.rays shouldBe 0
            f.chunkChecks shouldBe 2
            // Only isChunkLoaded is stubbed; getChunkAt/loadChunk would fail this fixture.
            citizenCanReachBlock(f.npc, CivPosition(16, 320, 0)) shouldBe false
            f.rays shouldBe 0
        }
    }

    test("a top-down tree batch focuses its reachable root and never accepts an unrelated focus") {
        val root = CivPosition(0, 64, 0)
        val changes = listOf(CivPosition(0, 70, 0), CivPosition(0, 68, 0), root)
            .map { CivPlanBlock(it, "minecraft:oak_log[axis=y]", "minecraft:air") }
        val work = CivWorldWork(UUID.randomUUID(), 0, UUID.randomUUID(), CivPosition(1, 64, 0), changes,
            status = "chopping-tree")
        citizenWorkFocus(work) shouldBe root
        citizenWorkFocus(work.copy(focus = root)) shouldBe root
        citizenWorkFocus(work.copy(focus = CivPosition(8, 64, 8))) shouldBe null
        citizenWorkFocus(work.copy(changes = emptyList())) shouldBe null
    }
})

private fun reachScenario(block: () -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { block() }
}

/** This fixture supplies the native ray result; it does not reproduce Paper's ray algorithm. */
private class WorkReachFixture {
    val world = mockk<World>()
    val npc = mockk<PacketPlayerNpc>()
    var at = Location(world, .5, 64.0, .5)
    var unloadedX: Int? = null
    var hit: RayTraceResult? = null
    var rays = 0
    var chunkChecks = 0
    var lastRayStart: Location? = null
    var lastRayLength: Double? = null

    init {
        every { npc.isValid } returns true
        every { npc.world } returns world
        every { npc.location } answers { at.clone() }
        every { npc.eyeHeight } returns 1.62
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { chunkChecks++; firstArg<Int>() != unloadedX }
        every { world.rayTraceBlocks(any<Location>(), any<Vector>(), any<Double>(), FluidCollisionMode.ALWAYS, true) } answers {
            rays++
            lastRayStart = firstArg<Location>().clone()
            lastRayLength = thirdArg()
            hit
        }
    }

    fun hitAt(point: CivPosition): RayTraceResult {
        val block = mockk<Block> {
            every { x } returns point.x
            every { y } returns point.y
            every { z } returns point.z
        }
        return RayTraceResult(Vector(point.x + .5, point.y + .5, point.z + .5), block, BlockFace.WEST)
    }
}
