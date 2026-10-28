package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.block.FluidLevelChangeEvent

class CivsResidentTerrainEventsTest : FunSpec({
    test("draining fluid invalidates the exact worksite cell while a cancelled fluid tick leaves it unchanged") {
        residentRuntime { h ->
            val point = CivPosition(5, 64, 5)
            val block = h.world.getBlockAt(point.x, point.y, point.z).apply { type = Material.WATER }
            val changed = mutableListOf<Pair<java.util.UUID, CivPosition>>()
            h.owner.worksiteTerrainChanged = { world, at -> changed += world to at }
            val draining = FluidLevelChangeEvent(block, Bukkit.createBlockData(Material.AIR))
            h.paper.callEvent(draining)
            changed shouldBe listOf(h.world.uid to point)
            changed.clear()
            h.paper.callEvent(FluidLevelChangeEvent(block, Bukkit.createBlockData(Material.AIR)).also { it.isCancelled = true })
            changed shouldBe emptyList()
        }
    }
})
