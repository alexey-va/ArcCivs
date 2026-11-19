package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketNpcPose

class CivsCitizenTraversalVisualsTest : FunSpec({
    test("the iron eye meets the rope throughout the cast and the plate stays on all four wall faces during pulls") {
        fun point(t: Transformation, x: Float, y: Float, z: Float) =
            t.leftRotation.transform(Vector3f(x, y, z).mul(t.scale)).add(t.translation)
        val low = CitizenPoint(18_000.5, 64.0, -2400.5)
        for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val anchor = CitizenPoint(low.x + dx * .5, 67.88, low.z + dz * .5)
            var lastLength = 0f
            for (tick in 2..12) {
                val frame = civsGrapplePose(low, anchor, low, 0f, 1.0, tick / 12.0)
                val eye = point(frame.hook, .5f, .494f, .604f)
                (point(frame.rope, .5f, 1f, .5f).distance(eye) < .00001f) shouldBe true
                (frame.rope.scale.y >= lastLength) shouldBe true
                lastLength = frame.rope.scale.y
            }
            for (y in listOf(64.0, 65.0, 67.0, 68.0)) {
                val frame = civsGrapplePose(low, anchor, low.copy(y = y), 90f, .5, 1.0)
                val plate = point(frame.hook, .5f, .36f, 1f)
                (plate.distance(Vector3f(dx * .5f, 3.88f, dz * .5f)) < .00001f) shouldBe true
            }
        }
    }

    test("only a nearby observer gets two stable props, alternating strokes, current hands and clean late entry") {
        residentRuntime { fixture ->
            val actor = fixture.actor()
            val npc = actor.npc
            val visual = fixture.owner.traversal
            val parts = mutableListOf<PacketBlockDisplay>()
            val materials = mutableListOf<Material>()
            val gestures = mutableListOf<String>()
            val displays = mockk<PaperPacketDisplays>()
            every { displays.spawnBlock(any(), any()) } answers {
                materials += secondArg<org.bukkit.block.data.BlockData>().material
                mockk<PacketBlockDisplay>(relaxed = true).also(parts::add)
            }
            every { npc.viewRange } returns 32.0
            every { npc.swingMainHand() } answers { gestures += "right" }
            every { npc.swingOffHand() } answers { gestures += "left" }
            visual.displays = displays
            val low = npc.location.point()
            val anchor = low.copy(x = low.x + .5, y = low.y + 3.88)
            val near = listOf(CitizenViewer(actor.worldId, low))
            fun frame(cast: Double, stroke: Int, viewers: List<CitizenViewer> = near) =
                visual.grapple(actor, low, anchor, cast, stroke, viewers)
            frame(.083, 0, emptyList())
            frame(.5, 0, listOf(CitizenViewer(actor.worldId, low.copy(x = low.x + 25))))
            actor.grappleVisual shouldBe null
            frame(.083, 0)
            verify { npc.setEquipment(EquipmentSlot.OFF_HAND, match { it.type == Material.TRIPWIRE_HOOK }) }
            frame(.5, 0)
            frame(1.0, 1)
            frame(1.0, 1)
            frame(1.0, 2)
            gestures shouldBe listOf("left", "right", "left")
            materials shouldBe listOf(Material.TRIPWIRE_HOOK, Material.STRIPPED_OAK_LOG)
            actor.equip(EquipmentSlot.HAND, ItemStack(Material.IRON_AXE))
            actor.equip(EquipmentSlot.OFF_HAND, ItemStack(Material.SHIELD))
            frame(1.0, 2, emptyList())
            parts.forEach { verify(exactly = 1) { it.remove() } }
            verify { npc.setEquipment(EquipmentSlot.HAND, match { it.type == Material.IRON_AXE }) }
            verify { npc.setEquipment(EquipmentSlot.OFF_HAND, match { it.type == Material.SHIELD }) }
            frame(1.0, 4)
            gestures.size shouldBe 3
            parts.size shouldBe 4
            visual.clear(actor)
            visual.clear(actor)
            parts.forEach { verify(exactly = 1) { it.remove() } }
            visual.crawl(actor, true)
            npc.pose shouldBe PacketNpcPose.SWIMMING
            visual.crawl(actor, false)
            npc.pose shouldBe PacketNpcPose.STANDING
            npc.pose = PacketNpcPose.SLEEPING
            visual.crawl(actor, false)
            npc.pose shouldBe PacketNpcPose.SLEEPING
        }
    }
})
