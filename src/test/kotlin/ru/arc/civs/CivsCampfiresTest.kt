package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.Lightable
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.function.Predicate

class CivsCampfiresTest : FunSpec({
    test("fireplace seats need a clear view on their actual side of walls and floors") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("campfire-visibility").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val fire = CivPosition(4, 64, 4)
            world.getBlockAt(fire.x, fire.y, fire.z).type = Material.CAMPFIRE
            val outside = CivPosition(6, 64, 4)
            val inside = CivPosition(2, 64, 4)
            citizenCampfireVisible(world, outside, fire) shouldBe true
            // The front of the fireplace remains open; its rear wall separates the exterior seat.
            for (y in 64..66) world.getBlockAt(5, y, 4).type = Material.STONE
            citizenCampfireVisible(world, outside, fire) shouldBe false
            citizenCampfireVisible(world, inside, fire) shouldBe true
            citizenCampfireVisible(world, Location(world, 6.1, 65.62, 4.5), fire) shouldBe false
            // Recheck existing visits: a newly placed wall must stop a previously valid seat.
            world.getBlockAt(3, 65, 4).type = Material.STONE
            citizenCampfireVisible(world, inside, fire) shouldBe false
            world.getBlockAt(3, 65, 4).type = Material.AIR
            citizenCampfireVisible(world, inside, fire) shouldBe true
            world.getBlockAt(4, 65, 4).type = Material.STONE
            citizenCampfireVisible(world, CivPosition(3, 66, 4), fire) shouldBe false
            val before = world.rayTraces
            citizenCampfireVisible(world, CivPosition(14, 64, 4), CivPosition(16, 64, 4)) shouldBe false
            world.rayTraces shouldBe before // An unloaded target is rejected without a native ray or a chunk load.
            world.isChunkLoaded(1, 0) shouldBe false
        }
    }

    test("social visits use idle time or an evening boundary and never interrupt work cargo or sleep") {
        val plan = CitizenPlan(CitizenToken(null, 0, 0, null), CivPosition(0, 64, 0), null, null, 0, "unassigned")
        citizenCampEligible(plan, CitizenPhase.IDLE, 6_000) shouldBe true
        citizenCampEligible(plan.copy(idleReason = "waiting-growth"), CitizenPhase.IDLE, 6_000) shouldBe true
        citizenCampEligible(plan.copy(idleReason = "waiting-tool"), CitizenPhase.IDLE, 6_000) shouldBe true
        val working = plan.copy(idleReason = null)
        citizenCampEligible(working, CitizenPhase.IDLE, 6_000) shouldBe false
        citizenCampEligible(working, CitizenPhase.IDLE, 11_000) shouldBe true
        for (phase in CitizenPhase.entries.filter { it != CitizenPhase.IDLE })
            citizenCampEligible(working, phase, 11_000) shouldBe false
        for (time in listOf(12_000L, 18_000L, 23_000L))
            citizenCampEligible(plan, CitizenPhase.IDLE, time) shouldBe false
        citizenCampEligible(plan.copy(idleReason = "placing"), CitizenPhase.IDLE, 11_000) shouldBe false
        citizenCampEligible(plan.copy(courier = mockk()), CitizenPhase.IDLE, 11_000) shouldBe false
    }

    test("six fire places are separated and each visit has bounded travel rest and an evening cooldown") {
        val worker = UUID(0, 7)
        val fire = CitizenCampfire(UUID(0, 1), CivPosition(4, 64, 4))
        val seats = citizenCampSeats(fire.position, worker).toList()
        seats.size shouldBe 18
        val columns = seats.map { it.x to it.z }.distinct()
        columns.size shouldBe 6
        for ((x, z) in columns) {
            ((x - 4) * (x - 4) + (z - 4) * (z - 4) in 4..5) shouldBe true
            for ((otherX, otherZ) in columns.filter { it != (x to z) })
                ((x - otherX) * (x - otherX) + (z - otherZ) * (z - otherZ) >= 4) shouldBe true
        }
        val routine = CitizenCampRoutine(worker)
        routine.start(fire, seats.first(), 100, 4, evening = true)
        routine.expired(999) shouldBe false
        routine.expired(1_000) shouldBe true
        routine.visit!!.arrivedAt = 600
        routine.expired(600 + routine.restTicks - 1) shouldBe false
        routine.expired(600 + routine.restTicks) shouldBe true
        routine.finish(1_500)
        routine.available(1_600, 4, evening = false) shouldBe false
        routine.available(10_000, 4, evening = false) shouldBe true
        routine.available(10_000, 4, evening = true) shouldBe false
        routine.available(10_000, 5, evening = true) shouldBe true
    }

    test("discovery reads one loaded chunk per pulse reuses its cache and invalidates extinguished fires") {
        MockBukkitTestRuntime.open().use {
            val worldId = UUID(0, 1)
            val world = mockk<World>()
            var lit = true
            var secondLoaded = true
            every { world.uid } returns worldId
            every { world.isChunkLoaded(any(), any()) } answers {
                secondArg<Int>() == 0 && (firstArg<Int>() == 0 || firstArg<Int>() == 1 && secondLoaded)
            }
            var scans = 0
            for (x in 0..1) {
                val chunk = mockk<Chunk>()
                val block = mockk<Block>()
                val data = mockk<Lightable>()
                val state = mockk<BlockState>()
                every { block.type } returns Material.CAMPFIRE
                every { block.blockData } returns data
                every { data.isLit } answers { lit }
                every { state.x } returns (4 + x * 16)
                every { state.y } returns 64
                every { state.z } returns 4
                every { world.getChunkAt(x, 0, false) } returns chunk
                every { chunk.getTileEntities(any<Predicate<Block>>(), false) } answers {
                    scans++
                    if (firstArg<Predicate<Block>>().test(block)) listOf(state) else emptyList()
                }
            }
            val city = Settlement(UUID(0, 2), UUID(0, 3), "Town", worldId, CivPosition(4, 64, 4), 96,
                "nordic", Stock(), emptyList(), emptyList())
            val index = CivsCampfires()
            val anchor = CivPosition(4, 64, 4)
            index.nearby(world, city, anchor, 0).size shouldBe 0
            index.scanOne(0) { world }
            scans shouldBe 1
            index.nearby(world, city, anchor, 20).size shouldBe 1
            index.scanOne(20) { world }
            scans shouldBe 2
            index.nearby(world, city, anchor, 40).size shouldBe 2
            index.scanOne(40) { world }
            scans shouldBe 2
            index.invalidate(worldId, 0, 0)
            lit = false
            index.nearby(world, city, anchor, 60).size shouldBe 1
            index.scanOne(60) { world }
            index.nearby(world, city, anchor, 80).size shouldBe 1
            secondLoaded = false
            index.nearby(world, city, anchor, 100).size shouldBe 0
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }
            index.clear()
        }
    }
})
