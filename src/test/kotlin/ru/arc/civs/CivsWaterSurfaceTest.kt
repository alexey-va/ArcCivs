package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsWaterSurfaceTest : FunSpec({
    test("water classification keeps exact one seven and twelve block depths unavailable for ordinary building") {
        for (depth in listOf(1, 7, 12)) {
            val column = requireNotNull(civsWaterSurfaceColumn(62, 62 - depth, Material.GRAVEL, true))
            column shouldBe CivTerrainSurfaceColumn(62, natural = false, waterTopY = 62, waterBottomY = 62 - depth)
        }
    }

    test("water classification refuses unproved natural beds structures lava and invalid depth") {
        for (bottom in listOf(Material.WATER, Material.LAVA, Material.AIR, Material.OAK_PLANKS,
            Material.STONE_BRICKS, Material.CHEST, Material.DIRT_PATH)) {
            civsWaterSurfaceColumn(62, 55, bottom, true) shouldBe null
        }
        civsWaterSurfaceColumn(62, 55, Material.GRAVEL, false) shouldBe null
        for (depth in listOf(-1, 0, 13)) civsWaterSurfaceColumn(62, 62 - depth, Material.GRAVEL, true) shouldBe null
        civsWaterSurfaceColumn(Int.MAX_VALUE, Int.MIN_VALUE, Material.GRAVEL, true) shouldBe null
    }

    test("native water survey is opt in per column and changes neither water bed nor ordinary admission") {
        waterSurfaceScenario { s ->
            s.water(0, 0, 7)
            s.water(1, 0, 7)
            val ordinary = s.capture()
            for (x in 0..1) ordinary.at(x, 0) shouldBe CivTerrainSurfaceColumn(62, natural = false)
            val surveyed = s.capture(setOf(civsTerrainKey(0, 0)))
            surveyed.at(0, 0) shouldBe CivTerrainSurfaceColumn(62, natural = false, waterTopY = 62, waterBottomY = 55)
            surveyed.at(1, 0) shouldBe ordinary.at(1, 0)
            for (x in 0..1) {
                s.world.getBlockAt(x, 55, 0).type shouldBe Material.GRAVEL
                for (y in 56..62) s.world.getBlockAt(x, y, 0).type shouldBe Material.WATER
            }
            s.city.stock shouldBe Stock()
            s.city.buildings shouldBe emptyList()
        }
    }

    test("native water survey stops at its depth limit and refuses mixed liquid air pockets bridges and crafted beds") {
        waterSurfaceScenario { s ->
            s.water(0, 0, 7)
            s.water(1, 0, 12)
            s.water(0, 1, 13)
            s.water(1, 1, 7, bottom = Material.STONE_BRICKS)
            s.water(-1, 0, 7, liquid = Material.LAVA)
            s.water(-1, -1, 7)
            s.world.getBlockAt(-1, 60, -1).type = Material.AIR
            s.water(-1, 1, 7)
            s.world.getBlockAt(-1, 64, 1).type = Material.OAK_PLANKS
            s.water(0, -1, 7)
            s.world.getBlockAt(0, 60, -1).type = Material.LAVA
            s.water(1, -1, 7, bottom = Material.DIRT_PATH)
            val columns = (-1..1).flatMap { x -> (-1..1).map { z -> civsTerrainKey(x, z) } }.toSet()
            val surface = s.capture(columns)
            surface.at(0, 0)!!.waterBottomY shouldBe 55
            surface.at(1, 0)!!.waterBottomY shouldBe 50
            for ((x, z) in listOf(0 to 1, 1 to 1, -1 to 0, -1 to -1, -1 to 1, 0 to -1, 1 to -1)) {
                val column = requireNotNull(surface.at(x, z))
                column.natural shouldBe false
                column.waterTopY shouldBe null
                column.waterBottomY shouldBe null
                column.dirtPath shouldBe false
            }
            s.world.getBlockAt(0, 50, 1).type shouldBe Material.WATER
            s.world.getBlockAt(-1, 64, 1).type shouldBe Material.OAK_PLANKS
            s.world.getBlockAt(1, 55, 1).type shouldBe Material.STONE_BRICKS
        }
    }

    test("surface dirt path evidence never marks other artificial terrain or submerged paths as roads") {
        waterSurfaceScenario { s ->
            s.world.getBlockAt(0, 63, 0).type = Material.DIRT_PATH
            s.world.getBlockAt(1, 63, 0).type = Material.COARSE_DIRT
            s.world.getBlockAt(0, 63, 1).type = Material.COBBLESTONE
            s.world.getBlockAt(1, 63, 1).type = Material.OAK_PLANKS
            val surface = s.capture()
            surface.at(0, 0) shouldBe CivTerrainSurfaceColumn(63, natural = false, dirtPath = true)
            surface.at(1, 0) shouldBe CivTerrainSurfaceColumn(63, natural = true)
            for ((x, z) in listOf(0 to 1, 1 to 1)) surface.at(x, z) shouldBe CivTerrainSurfaceColumn(63, natural = false)
            s.world.getBlockAt(0, 63, 0).type shouldBe Material.DIRT_PATH
        }
    }

    test("an explicitly requested unavailable water column stays unloaded without water evidence") {
        waterSurfaceScenario { s ->
            s.city = s.city.copy(radius = 32)
            s.world.isChunkLoaded(2, 0) shouldBe false
            val column = requireNotNull(s.capture(setOf(civsTerrainKey(32, 0))).at(32, 0))
            column.loaded shouldBe false
            column.natural shouldBe false
            column.waterTopY shouldBe null
            column.waterBottomY shouldBe null
            s.world.isChunkLoaded(2, 0) shouldBe false
        }
    }
})

private class WaterSurfaceScenario(paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = CivsWorksiteWorldFixture("water-surface").also(paper.server::addWorld)
    private val building = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
        "test", CivBox(CivPosition(0, 64, 0), CivPosition(0, 64, 0)), CivPosition(0, 64, 0), BuildingPhase.CONSTRUCTING)
    var city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Water surface", world.uid, CivPosition(0, 64, 0),
        1, "test", Stock(), emptyList(), emptyList(), autonomy = CivAutonomy())
    private val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    private val subject = CivsWorld(paper.createSimplePlugin("WaterSurfaceTest"), tasks, { 0L }) { listOf(city) }
    init { for (x in -1..0) for (z in -1..0) world.loadChunk(x, z) }

    fun water(x: Int, z: Int, depth: Int, bottom: Material = Material.GRAVEL, liquid: Material = Material.WATER) {
        val bed = 62 - depth
        world.getBlockAt(x, bed, z).type = bottom
        for (y in bed + 1..63) world.getBlockAt(x, y, z).type = if (y <= 62) liquid else Material.AIR
    }

    fun capture(waterColumns: Set<Long> = emptySet()): CivTerrainSurface {
        val capture = subject.autonomousHeightField(city, building, waterColumns = waterColumns)
        scheduler.tick(4)
        check(capture.isDone) { "Bounded native surface capture did not finish" }
        return capture.join()
    }

    override fun close() { subject.close(); tasks.close() }
}

private fun waterSurfaceScenario(action: (WaterSurfaceScenario) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> WaterSurfaceScenario(paper).use(action) }
}
