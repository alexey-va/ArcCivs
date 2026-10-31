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

    test("ordinary hall work and storage bind the real barrel instead of the entrance") {
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.type shouldBe BuildingType.TOWN_HALL
        plan.workplace shouldBe hall.control
        plan.storage shouldBe hall.control
        plan.spawnOrigin shouldBe hall.control
        citizenPlan(city.copy(buildings = listOf(hall.copy(control = null), hut)), worker)?.idleReason shouldBe "building-unavailable"
    }

    test("storage accepts its own interior but rejects a foreign footprint and never stands on the barrel") {
        val world = storageWorld(city.worldId, hall.control!!)
        val east = CivPosition(1, 64, -4)
        citizenStorageTarget(city, hall, world) { true } shouldBe east
        val obstruction = hut.copy(bounds = CivBox(CivPosition(1, 63, -5), CivPosition(2, 67, -3)))
        citizenStorageTarget(city.copy(buildings = listOf(hall, obstruction)), hall, world) { true } shouldBe CivPosition(-1, 64, -4)
        val visited = mutableListOf<CivPosition>()
        citizenStorageTarget(city, hall, world) { visited += it; false } shouldBe null
        visited.size shouldBe 24
        visited.all { citizenAtStorage(world, it.point(), hall.control!!) } shouldBe true
        visited.none { it.x == hall.control!!.x && it.z == hall.control!!.z } shouldBe true
        citizenStorageTarget(city, hall, world) { it == hall.control!!.copy(y = 65) } shouldBe null
        every { world.getBlockAt(0, 64, -4).type } returns Material.AIR
        citizenStorageTarget(city, hall, world) { true } shouldBe null
    }

    test("scribe fixture finds a raised supported floor and never reads unloaded cells") {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>()
            every { world.uid } returns city.worldId
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
                    every { type } returns if (CivPosition(x, y, z) == hall.control) Material.BARREL else if (solid) Material.STONE else Material.AIR
                    every { isPassable } returns !solid
                    every { isLiquid } returns false
                    every { boundingBox } returns BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0,
                        y + if (solid) 1.0 else 0.0, z + 1.0)
                }
            }
            citizenStorageTarget(city, hall, world) shouldBe CivPosition(1, 65, -4)
            every { world.isChunkLoaded(any(), any()) } returns false
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } throws AssertionError("No unloaded reads")
            citizenStorageTarget(city, hall, world) shouldBe null
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
        }
    }

    test("barrel arrival rejects the distant entrance the barrel top and an unloaded or missing barrel") {
        val control = hall.control!!
        val world = storageWorld(city.worldId, control)
        citizenAtStorage(world, CitizenPoint(hall.entrance.x + 0.5, hall.entrance.y.toDouble(), hall.entrance.z + 0.5), control) shouldBe false
        citizenAtStorage(world, CitizenPoint(1.5, 64.0, -3.5), control) shouldBe true
        citizenAtStorage(world, CitizenPoint(0.5, 65.0, -3.5), control) shouldBe false
        every { world.isChunkLoaded(any(), any()) } returns false
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } throws AssertionError("No unloaded reads")
        citizenAtStorage(world, CitizenPoint(1.5, 64.0, -3.5), control) shouldBe false
    }

    test("all professions avoid control barrel footholds and diagonal shortcuts including a new construction site") {
        val planned = hut.copy(control = CivPosition(12, 64, 2), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), planned.id, 1, 2, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = planned)
        val current = city.copy(buildings = listOf(hall), construction = order)
        citizenControlBuilding(current, planned.control!!) shouldBe planned
        val control = hall.control!!
        citizenControlClear(current, control) shouldBe false
        citizenControlClear(current, control.copy(y = control.y + 1)) shouldBe false
        citizenControlClear(current, control.copy(x = control.x + 1)) shouldBe true
        citizenControlClear(current, planned.control!!.copy(y = 65)) shouldBe false
        citizenControlStepClear(current, CitizenPoint(1.5, 64.0, -3.5), CitizenPoint(.5, 64.0, -4.5)) shouldBe false
        citizenControlStepClear(current, CitizenPoint(1.5, 64.0, -3.5), CitizenPoint(1.5, 64.0, -4.5)) shouldBe true
        val plan = CitizenPlan(CitizenToken(hut.id, 0, 0, order.id), hall.control!!, planned.control,
            BuildingType.BUILDER_HUT, 1_000, null)
        citizenRouteBuildings(current, plan, planned.control!!, hall.entrance).contains(planned.id) shouldBe true
    }
})

private fun storageWorld(worldId: UUID, control: CivPosition): World = mockk {
    every { uid } returns worldId
    every { isChunkLoaded(any(), any()) } returns true
    every { getBlockAt(control.x, control.y, control.z) } returns mockk { every { type } returns Material.BARREL }
}
