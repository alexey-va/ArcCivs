package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsAcceptedConstructionTerrainTest : FunSpec({
    test("accepted Auto2 upgrade survives leaf ticks and later changes of the captured terrain") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("auto2-leaf-upgrade")
            world.loadChunk(-1875 shr 4, -2694 shr 4)
            val scheduler = TestTaskScheduler()
            LifecycleTaskScope(scheduler, true).use { tasks ->
                val building = CivBuilding(UUID.randomUUID(), BuildingType.FORESTER, 1,
                    CivPosition(-1873, 64, -2693), 0, "test",
                    CivBox(CivPosition(-1878, 63, -2700), CivPosition(-1860, 80, -2684)),
                    CivPosition(-1873, 64, -2694), BuildingPhase.ACTIVE)
                val planned = building.copy(level = 2)
                val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Auto2", world.uid,
                    CivPosition(-1883, 67, -2760), 192, "test", Stock(), listOf(building), emptyList(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy())
                val point = CivPosition(-1875, 66, -2694)
                val before = "minecraft:oak_leaves[distance=4,persistent=false,waterlogged=false]"
                val actual = "minecraft:oak_leaves[distance=5,persistent=false,waterlogged=false]"
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(actual)
                val plan = CivPlacementPlan(UUID.randomUUID(), city.id, city.worldId, planned,
                    listOf(CivPlanBlock(point, before, "minecraft:air")))
                CivsWorld(paper.createSimplePlugin("Auto2LeafUpgrade"), tasks, { 0L }) { listOf(city) }.use { subject ->
                    for (state in listOf(actual, "minecraft:stone", "minecraft:oak_planks", "minecraft:air")) {
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(state)
                        val result = subject.revalidate(plan, city.owner)
                        scheduler.tick()
                        result.isDone shouldBe true
                        result.join() shouldBe Unit
                        world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe Bukkit.createBlockData(state).asString
                    }
                }
            }
        }
    }

    test("leaf distance tolerance never accepts a different species persistence water or material") {
        MockBukkitTestRuntime.open().use {
            val expected = Bukkit.createBlockData("minecraft:oak_leaves[distance=4,persistent=false,waterlogged=false]")
            for (distance in 1..7) {
                val actual = Bukkit.createBlockData("minecraft:oak_leaves[distance=$distance,persistent=false,waterlogged=false]")
                CivsWorld.sameConstructionState(actual, expected) shouldBe true
            }
            for (state in listOf("minecraft:birch_leaves[distance=5,persistent=false,waterlogged=false]",
                "minecraft:oak_leaves[distance=5,persistent=true,waterlogged=false]",
                "minecraft:oak_leaves[distance=5,persistent=false,waterlogged=true]", "minecraft:stone")) {
                CivsWorld.sameConstructionState(Bukkit.createBlockData(state), expected) shouldBe false
            }
            CivsWorld.sameConstructionState(Material.AIR.createBlockData(), expected) shouldBe false
        }
    }
})
