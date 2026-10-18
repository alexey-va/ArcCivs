package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.Block
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsContainerIndexTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var index: CivsContainerIndex
    lateinit var city: Settlement
    lateinit var tiles: MutableList<Block>
    lateinit var reads: MutableList<Pair<Int, Int>>

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.addSimpleWorld("container-index")
        world.loadChunk(0, 0)
        tiles = mutableListOf()
        reads = mutableListOf()
        index = CivsContainerIndex(paper.server) { chunk ->
            reads += chunk.x to chunk.z
            tiles.filter { (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z }.map(Block::getState).toTypedArray()
        }
        val control = CivPosition(0, 64, 0)
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, control, 0, "index",
            CivBox(CivPosition(0, 64, 0), CivPosition(15, 66, 15)), CivPosition(0, 64, -1), BuildingPhase.SITE, control)
        city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Index", world.uid, control, 32, "nordic", hall, 1_000)
    }
    afterEach { index.clear(); paper.close() }

    test("ordinary placement and nongeometric revisions keep the same cached positions without reads or rebuilds") {
        val chest = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }
        tiles += chest
        val original = index.positions(city)
        val ordinary = world.getBlockAt(3, 64, 0).also { it.type = Material.STONE }
        repeat(5) { index.changed(ordinary) }
        (index.positions(city) === original) shouldBe true
        val revised = city.copy(revision = 1, inventory = requireNotNull(city.inventory).copy(
            mode = CivStorageMode.VIRTUAL, materials = mapOf("BREAD" to 12L)))
        (index.positions(revised) === original) shouldBe true
        reads shouldBe listOf(0 to 0)
        index.changed(chest)
        (index.positions(city) === original) shouldBe true
        chest.type = Material.TRAPPED_CHEST
        index.changed(chest)
        index.positions(city)[CivPosition(2, 64, 0)] shouldBe "TRAPPED_CHEST"
        reads shouldBe listOf(0 to 0)
    }

    test("explicit replace rescans unchanged geometry while ordinary updates preserve the cache") {
        index.replace(listOf(city))
        val chest = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }
        tiles += chest
        index.update(city)
        (CivPosition(2, 64, 0) in index.positions(city)) shouldBe false
        reads shouldBe listOf(0 to 0)
        index.replace(listOf(city))
        index.positions(city)[CivPosition(2, 64, 0)] shouldBe "CHEST"
        reads shouldBe listOf(0 to 0, 0 to 0)
        index.replace(emptyList())
        val afterRemove = reads.size
        index.load(world.getChunkAt(0, 0))
        reads.size shouldBe afterRemove
    }

    test("only overlapping loaded chunks are inspected and unload retains no container positions") {
        city = city.copy(buildings = listOf(city.buildings.single().copy(
            bounds = CivBox(CivPosition(0, 64, 0), CivPosition(31, 66, 15)))))
        val near = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }
        val far = world.getBlockAt(20, 64, 0).also { it.type = Material.BARREL }
        tiles += listOf(near, far)
        world.unloadChunk(1, 0)
        world.loadChunk(3, 0) // Loaded but outside every building.
        index.update(city)
        reads shouldBe listOf(0 to 0)
        world.isChunkLoaded(1, 0) shouldBe false
        (CivPosition(20, 64, 0) in index.positions(city)) shouldBe false
        world.loadChunk(1, 0)
        val chunk = world.getChunkAt(1, 0)
        index.load(chunk)
        index.positions(city)[CivPosition(20, 64, 0)] shouldBe "BARREL"
        reads shouldBe listOf(0 to 0, 1 to 0)
        index.unload(chunk)
        world.unloadChunk(1, 0)
        (CivPosition(20, 64, 0) in index.positions(city)) shouldBe false
        reads shouldBe listOf(0 to 0, 1 to 0)
        world.isChunkLoaded(1, 0) shouldBe false
    }

    test("geometry changes reclassify height and extent while registered outside storage remains compatible") {
        val inside = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }
        val above = world.getBlockAt(2, 67, 0).also { it.type = Material.BARREL }
        val explicit = CivPosition(20, 64, 0)
        tiles += listOf(inside, above)
        city = city.copy(inventory = requireNotNull(city.inventory).copy(containers = listOf(explicit)))
        index.update(city)
        index.positions(city).keys shouldBe setOf(CivPosition(0, 64, 0), CivPosition(2, 64, 0), explicit)
        city = city.copy(buildings = listOf(city.buildings.single().copy(
            bounds = CivBox(CivPosition(0, 65, 0), CivPosition(15, 67, 15)))))
        index.update(city)
        index.positions(city).keys shouldBe setOf(CivPosition(0, 64, 0), CivPosition(2, 67, 0), explicit)
        reads shouldBe listOf(0 to 0, 0 to 0)
    }
})
