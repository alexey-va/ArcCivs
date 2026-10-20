package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsResidentNavigationTest : FunSpec({
    test("packet movement walks around an occupied body-width corner and rejects an inserted wall") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (z in -2..2) for (y in 64..65) terrain.blocks[CivPosition(2, y, z)] = Material.STONE
            val route = terrain.route()
            repeat(20_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            route.searching shouldBe false
            var at = Location(terrain.world, .5, 64.0, .5)
            var detoured = false
            repeat(200) {
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketBodyClear(terrain.world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    if (kotlin.math.abs(next.z - .5) >= 2.5) detoured = true
                    at = next
                }
            }
            route.finished shouldBe true
            detoured shouldBe true
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0

            val changed = ResidentRouteTerrain()
            val second = changed.route()
            repeat(2_000) { if (second.searching) second.expand() }
            val next = requireNotNull(second.destination)
            changed.blocks[CivPosition(next.blockX, 64, next.blockZ)] = Material.STONE
            second.move(Location(changed.world, .5, 64.0, .5), 1.0) { true } shouldBe null
            second.failure shouldBe "movement-blocked"
        }
    }

    test("one shared admission budget gives every queued route progress and cancellation releases search") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val owner = CivsResidentNavigation()
            val routes = List(48) {
                owner.request(terrain.world, Location(terrain.world, .5, 64.0, .5),
                    Location(terrain.world, 5.5, 64.0, .5), terrain::allowed, { _, _ -> true }, { 1.0 })
            }
            repeat(60) { owner.pulse() }
            routes.all { it.nodeCount > 0 } shouldBe true
            val searching = routes.filter { it.searching }
            owner.clear()
            searching.all { !it.searching && !it.navigating } shouldBe true
            routes.forEach(CitizenPacketRoute::cancel)
        }
    }

    test("one-block ascent and descent keep every intermediate body outside solid support") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
            val route = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 2.5, 64.0, .5),
                { point -> point.x in 0..2 && point.z == 0 && point.y in 64..65 }, { _, _ -> true }, { 1.0 })
            repeat(2_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            var climbed = false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketBodyClear(terrain.world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    if (next.y >= 65.0) climbed = true
                    at = next
                }
            }
            climbed shouldBe true
            route.finished shouldBe true
            route.failure shouldBe null
            at.distanceSquared(Location(terrain.world, 2.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("heading takes the short turn across wraparound and terrain cannot load a missing chunk") {
        citizenTurn(179f, -179f, 24f) shouldBe 181f
        citizenTurn(0f, 120f, 24f) shouldBe 24f
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.loaded = false
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe false
            terrain.blockReads shouldBe 0
        }
    }
})

/** Explicit geometric unit fixture. It does not model a live server or claim native client movement. */
private class ResidentRouteTerrain {
    val blocks = mutableMapOf<CivPosition, Material>()
    var loaded = true
    var blockReads = 0
    val world = mockk<World>()
    private val chunk = mockk<Chunk>()
    private val native = mutableMapOf<CivPosition, Block>()
    private val blockDataByMaterial = mutableMapOf<Material, org.bukkit.block.data.BlockData>()
    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns 0
        every { world.maxHeight } returns 256
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
        every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            blockReads++
            val point = CivPosition(firstArg(), secondArg(), thirdArg())
            native.getOrPut(point) {
                val shape = mockk<VoxelShape> {
                    every { boundingBoxes } answers {
                        if (material(point).isSolid) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()
                    }
                }
                val block = mockk<Block> {
                    every { type } answers { material(point) }
                    every { isLiquid } returns false
                    every { blockData } answers { blockDataByMaterial.getOrPut(material(point)) { material(point).createBlockData() } }
                    every { collisionShape } returns shape
                }
                // Geometry has explicit assertions/counters. Retaining every probe across
                // 48 routes only grows MockK's call history and is not part of this oracle.
                excludeRecords {
                    block.type; block.isLiquid; block.blockData; block.collisionShape
                    shape.boundingBoxes
                }
                block
            }
        }
        excludeRecords {
            world.uid; world.minHeight; world.maxHeight
            world.isChunkLoaded(any<Int>(), any<Int>())
            world.getChunkAt(any<Int>(), any<Int>(), false)
            world.getBlockAt(any<Int>(), any<Int>(), any<Int>())
            chunk.loadLevel
        }
    }
    private fun material(point: CivPosition) = blocks[point] ?: if (point.y <= 63) Material.STONE else Material.AIR
    fun allowed(point: CivPosition) = point.x in -3..8 && point.z in -5..5 && point.y in 64..65
    fun route() = CitizenPacketRoute(world, Location(world, .5, 64.0, .5), Location(world, 5.5, 64.0, .5),
        ::allowed, { _, _ -> true }, { 1.0 })
}
