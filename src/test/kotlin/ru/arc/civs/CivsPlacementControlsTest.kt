package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.actionpanel.PaperActionPanels
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsPlacementControlsTest : FunSpec({
    test("normal placement has six world actions and changing mode rejects the old confirmation context") {
        controlsScenario { h ->
            val initial = h.controls.frame(h.player)!!
            initial.layout.buttons.map { it.action } shouldBe listOf(CivsPlacementAction.CONFIRM, CivsPlacementAction.MOVE,
                CivsPlacementAction.ROTATE_LEFT, CivsPlacementAction.ROTATE_RIGHT, CivsPlacementAction.DETAILS, CivsPlacementAction.CANCEL)
            h.controls.activate(h.player, initial.context, CivsPlacementAction.MOVE)
            val moving = h.controls.frame(h.player)!!
            // Entering move mode must discard the normal panel's retained distance.
            verify(exactly = 1) { h.panels.clear(h.player.uniqueId) }
            moving.layout.buttons.map { it.action } shouldBe listOf(CivsPlacementAction.LEFT, CivsPlacementAction.RIGHT,
                CivsPlacementAction.UP, CivsPlacementAction.DOWN, CivsPlacementAction.TOWARD, CivsPlacementAction.AWAY,
                CivsPlacementAction.DONE, CivsPlacementAction.CANCEL)
            h.controls.activate(h.player, initial.context, CivsPlacementAction.CONFIRM)
            verify(exactly = 0) { h.host.confirmPreview(any()) }
            h.controls.activate(h.player, moving.context, CivsPlacementAction.DONE)
            h.controls.frame(h.player)!!.context.moving shouldBe false
        }
    }

    test("rotation has one in-flight request and a stale context cannot confirm its replacement") {
        controlsScenario { h ->
            val pending = CompletableFuture<Unit>()
            every { h.host.rotatePreview(any(), -90) } returns pending
            val before = h.controls.frame(h.player)!!
            h.controls.activate(h.player, before.context, CivsPlacementAction.ROTATE_LEFT)
            h.controls.activate(h.player, before.context, CivsPlacementAction.ROTATE_LEFT)
            h.controls.frame(h.player)!!.inputEnabled shouldBe false
            h.controls.frame(h.player)!!.layout.buttons.size shouldBe 0
            h.preview = h.preview!!.copy(id = UUID.randomUUID(), rotation = 270)
            pending.complete(Unit)
            h.paper.performTicks(2)
            h.controls.frame(h.player)!!.inputEnabled shouldBe true
            h.controls.activate(h.player, before.context, CivsPlacementAction.CONFIRM)
            verify(exactly = 1) { h.host.rotatePreview(h.player, -90) }
            verify(exactly = 0) { h.host.confirmPreview(any()) }
        }
    }

    test("move mode consumes only the main-hand placement gesture and returns to normal controls") {
        controlsScenario { h ->
            every { h.host.movePreview(any()) } answers {
                h.preview = h.preview!!.copy(id = UUID.randomUUID())
                CompletableFuture.completedFuture(Unit)
            }
            h.controls.activate(h.player, h.controls.frame(h.player)!!.context, CivsPlacementAction.MOVE)
            val event = mockk<PlayerInteractEvent>(relaxed = true)
            every { event.player } returns h.player
            every { event.action } returns Action.RIGHT_CLICK_BLOCK
            every { event.hand } returns EquipmentSlot.OFF_HAND
            h.controls.interact(event)
            verify(exactly = 0) { h.host.movePreview(any()) }
            every { event.hand } returns EquipmentSlot.HAND
            h.controls.interact(event)
            h.paper.performTicks(2)
            verify(exactly = 1) { event.isCancelled = true }
            verify(exactly = 1) { h.host.movePreview(h.player) }
            h.controls.frame(h.player)!!.context.moving shouldBe false
        }
    }

    test("details hide the panel and closing the dialog resumes the same placement") {
        controlsScenario { h ->
            val before = h.controls.frame(h.player)!!
            every { h.ui.open(h.player) } answers { h.dialog = true }
            h.controls.activate(h.player, before.context, CivsPlacementAction.DETAILS)
            h.controls.frame(h.player) shouldBe null
            h.dialog = false
            h.controls.frame(h.player)!!.context shouldBe before.context
            verify(exactly = 0) { h.host.cancelPreview(any()) }
        }
    }

    test("upgrade controls expose no movement and confirmation completes without opening a dialog") {
        controlsScenario { h ->
            h.preview = h.preview!!.copy(level = 2, movable = false)
            val frame = h.controls.frame(h.player)!!
            frame.layout.buttons.map { it.action } shouldBe listOf(CivsPlacementAction.CONFIRM, CivsPlacementAction.DETAILS, CivsPlacementAction.CANCEL)
            every { h.host.confirmPreview(any()) } answers {
                h.preview = null
                CompletableFuture.completedFuture(Unit)
            }
            h.controls.activate(h.player, frame.context, CivsPlacementAction.CONFIRM)
            h.paper.performTicks(2)
            verify(exactly = 1) { h.host.confirmPreview(h.player) }
            verify(exactly = 0) { h.ui.open(any()) }
            h.controls.frame(h.player) shouldBe null
        }
    }

    test("nudges are cardinal and the green anchor is a visible twelve-edge block frame") {
        val center = CivPosition(4, 64, 9)
        for (facing in 0..3) {
            val moved = civsMovedEntrance(center, CivsPreviewMove.RIGHT, facing)
            civsMovedEntrance(moved, CivsPreviewMove.LEFT, facing) shouldBe center
            val away = civsMovedEntrance(center, CivsPreviewMove.AWAY, facing)
            civsMovedEntrance(away, CivsPreviewMove.TOWARD, facing) shouldBe center
            civsMovedEntrance(center, CivsPreviewMove.UP, facing) shouldBe center.copy(y = 65)
        }
        // Bukkit yaw 0 faces south (+Z); its screen-right is west (-X).
        val rightTargets = listOf(center.copy(x = 3), center.copy(z = 8), center.copy(x = 5), center.copy(z = 10))
        val awayTargets = listOf(center.copy(z = 10), center.copy(x = 3), center.copy(z = 8), center.copy(x = 5))
        for (facing in 0..3) {
            civsMovedEntrance(center, CivsPreviewMove.RIGHT, facing) shouldBe rightTargets[facing]
            civsMovedEntrance(center, CivsPreviewMove.AWAY, facing) shouldBe awayTargets[facing]
        }
        val cues = civsAnchorCues(center, Material.LIME_CONCRETE)
        cues.size shouldBe 12
        cues.all { it.material == Material.LIME_CONCRETE } shouldBe true
        (cues.minOf { it.x } < center.x && cues.maxOf { it.x + it.sx } > center.x + 1) shouldBe true
        (cues.minOf { it.y } < center.y && cues.maxOf { it.y + it.sy } > center.y + 1) shouldBe true
        (cues.minOf { it.z } < center.z && cues.maxOf { it.z + it.sz } > center.z + 1) shouldBe true
    }
})

private fun controlsScenario(action: (ControlsScenario) -> Unit) = MockBukkitTestRuntime.open().use { paper ->
    ControlsScenario(paper).use(action)
}

private class ControlsScenario(val paper: MockBukkitTestRuntime) : AutoCloseable {
    val plugin = paper.createSimplePlugin("CivsPlacementControlsTest")
    val player = paper.addPlayer("Mayor").apply { isOp = true }
    val host = mockk<CivsUiHost>(relaxed = true)
    val ui = mockk<CivsUi>(relaxed = true)
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val panels = mockk<PaperActionPanels<CivsPlacementContext, CivsPlacementAction>>(relaxed = true)
    var dialog = false
    var preview: CivsPreviewView? = CivsPreviewView(BuildingType.TOWN_HALL, 1, "nordic", "Oak Town",
        CivPosition(0, 63, 0), 0, 12, 10, 12, Stock(), 400, UUID.randomUUID(), player.world.uid,
        CivPosition(0, 64, 0), CivBox(CivPosition(-6, 63, -12), CivPosition(5, 72, -1)), true, false)
    val controls: CivsPlacementControls
    init {
        player.teleport(org.bukkit.Location(player.world, 0.5, 64.0, 4.5))
        every { host.ready() } returns true
        every { host.preview(player) } answers { preview }
        every { ui.showingDialog(player) } answers { dialog }
        every { panels.intercept(any()) } returns false
        controls = CivsPlacementControls(plugin, host, ui, tasks, CivsText.fromYaml(civsLocaleSource()), displays, panels)
    }
    override fun close() { controls.close(); tasks.close() }
}
