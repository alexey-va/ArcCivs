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
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsScribeWorkplaceTest : FunSpec({
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0, "test",
        CivBox(CivPosition(-3, 63, -8), CivPosition(3, 69, -1)), CivPosition(0, 64, 0),
        BuildingPhase.ACTIVE, control = CivPosition(0, 64, -4))
    val hut = hall.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT,
        bounds = CivBox(CivPosition(10, 63, 0), CivPosition(14, 69, 4)), entrance = CivPosition(12, 64, 5))
    val worker = CivWorker(UUID.randomUUID(), "Писарь", hall.id)
    val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Test", UUID.randomUUID(), CivPosition(0, 64, 0),
        48, "test", Stock(), listOf(hall, hut), listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL))

    test("ordinary hall work binds its barrel while spawn and storage stay at the entrance") {
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.type shouldBe BuildingType.TOWN_HALL
        plan.workplace shouldBe hall.control
        plan.storage shouldBe hall.entrance
        plan.spawnOrigin shouldBe hall.entrance
        citizenPlan(city.copy(buildings = listOf(hall.copy(control = null), hut)), worker)?.idleReason shouldBe "building-unavailable"
    }

    test("scribe accepts its own interior but rejects a foreign footprint and never stands on the barrel") {
        val plan = requireNotNull(citizenPlan(city, worker))
        val east = CivPosition(1, 64, -4)
        citizenScribeTarget(city, plan) { true } shouldBe east
        val obstruction = hut.copy(bounds = CivBox(CivPosition(1, 63, -5), CivPosition(2, 67, -3)))
        citizenScribeTarget(city.copy(buildings = listOf(hall, obstruction)), plan) { true } shouldBe CivPosition(-1, 64, -4)
        val visited = mutableListOf<CivPosition>()
        citizenScribeTarget(city, plan) { visited += it; false } shouldBe null
        visited.size shouldBe 12
        visited.none { it.x == hall.control!!.x && it.z == hall.control!!.z } shouldBe true
    }

    test("scribe fixture finds a raised supported floor and never reads unloaded cells") {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>()
            val chunk = mockk<Chunk>()
            every { world.isChunkLoaded(any(), any()) } returns true
            every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
            every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
                val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
                val solid = y <= 64
                mockk<Block> {
                    every { type } returns if (solid) Material.STONE else Material.AIR
                    every { isPassable } returns !solid
                    every { isLiquid } returns false
                    every { boundingBox } returns BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0,
                        y + if (solid) 1.0 else 0.0, z + 1.0)
                }
            }
            val plan = requireNotNull(citizenPlan(city, worker))
            citizenScribeTarget(city, plan) { citizenStandable(world, it) } shouldBe CivPosition(1, 65, -4)
            every { world.isChunkLoaded(any(), any()) } returns false
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } throws AssertionError("No unloaded reads")
            citizenScribeTarget(city, plan) { citizenStandable(world, it) } shouldBe null
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
        }
    }
})
