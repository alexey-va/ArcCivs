package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.TreeType
import org.bukkit.block.BlockState
import org.bukkit.event.world.StructureGrowEvent
import java.util.UUID

class CivsTreeTerrainEventTest : FunSpec({
    test("tree listeners preserve every accepted cell and skip block data for an unprotected crown") {
        residentRuntime { h ->
            val leaf = CivPosition(6, 90, 6)
            val log = CivPosition(7, 90, 6)
            var locked: CivPosition? = null
            val protection = CivsProtection({ emptyList() }, worldLocked = { world, point ->
                world == h.world.uid && point == locked
            })
            h.paper.server.pluginManager.registerEvents(protection, h.paper.createSimplePlugin("TreeTerrainProtection"))
            val observed = mutableListOf<CivPosition>()
            h.owner.worksiteTerrainChanged = { world, point -> if (world == h.world.uid) observed += point }
            fun proposed(point: CivPosition, material: Material): BlockState {
                val native = h.world.getBlockAt(point.x, point.y, point.z)
                native.type = Material.AIR
                return mockk<BlockState>().also { state ->
                    every { state.world } returns h.world
                    every { state.x } returns point.x
                    every { state.y } returns point.y
                    every { state.z } returns point.z
                    every { state.type } returns material
                    every { state.block } returns native
                    every { state.blockData } throws AssertionError("Unprotected crown reads proposed block data")
                }
            }
            val foliage = proposed(leaf, Material.OAK_LEAVES)
            val trunk = proposed(log, Material.OAK_LOG)
            fun grow() = h.paper.callEvent(StructureGrowEvent(Location(h.world, 6.0, 88.0, 6.0),
                TreeType.TREE, false, null, mutableListOf(foliage, trunk)))

            grow().isCancelled shouldBe false
            observed shouldBe listOf(leaf, log)
            verify(exactly = 0) { foliage.blockData }
            verify(exactly = 0) { trunk.blockData }
            verify(exactly = 1) { foliage.block }
            verify(exactly = 1) { trunk.block }

            observed.clear()
            locked = log
            grow().isCancelled shouldBe true
            observed shouldBe emptyList()
        }
    }

    test("rotated mine rewinds to the earliest affected checked wall or exposure") {
        val anchor = CivPosition(12, 70, -20)
        val building = CivBuilding(UUID.randomUUID(), BuildingType.QUARRY, 1, anchor, 270, "quarry",
            CivBox(anchor, anchor), anchor, BuildingPhase.ACTIVE)
        val worker = UUID.randomUUID()
        val mine = CivsMineWorksite(mockk(), mockk())
        val shared = CivPosition(1, -3, 2)
        val wall = CivPosition(0, -3, 2)
        val unchecked = CivPosition(2, -3, 2)
        val frontier = listOf(CivMineProtection(shared, wall, null), CivMineProtection(wall, shared, null),
            CivMineProtection(unchecked, unchecked, null))
        val scan = CivMineSafetyScan(building, shared, 0L, null, 0L, frontier, next = 2)
        mine.mineSafety[worker] = scan
        fun changed(local: CivPosition) = mine.changed(worker, building, CivsWorksiteLayouts.point(building, local))

        changed(unchecked)
        changed(CivPosition(90, 2, 67))
        scan.next shouldBe 2
        changed(shared)
        scan.next shouldBe 0
        scan.next = 2
        changed(wall)
        scan.next shouldBe 0
    }
})
