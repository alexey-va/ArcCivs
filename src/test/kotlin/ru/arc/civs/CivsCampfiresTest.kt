package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.Lightable
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.function.Predicate

class CivsCampfiresTest : FunSpec({
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
