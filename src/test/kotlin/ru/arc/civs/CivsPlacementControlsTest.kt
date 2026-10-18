package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.actionpanel.PaperActionPanels
import ru.arc.paper.display.PaperPacketDisplays
import net.kyori.adventure.text.format.TextColor
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsPlacementControlsTest : FunSpec({
    test("ground selection has no floating panel and only one main-hand click selects the actual block") {
        controlsScenario { h ->
            h.startSelection()
            val id = h.selection!!.id
            val pending = CompletableFuture<Unit>()
            every { h.host.selectGround(any(), any(), any()) } returns pending
            val ground = h.player.world.getBlockAt(2, 63, 2).also { it.type = Material.GRASS_BLOCK }
            val grass = h.player.world.getBlockAt(2, 64, 2).also { it.type = Material.SHORT_GRASS }
            val spawned = mutableListOf<Location>()
            every { h.displays.spawnBlock(capture(spawned), any()) } returns mockk(relaxed = true)
            h.controls.frame(h.player) shouldBe null
            val offhand = h.click(grass, EquipmentSlot.OFF_HAND)
            verify(exactly = 0) { h.host.selectGround(any(), any(), any()) }
            val first = h.click(grass)
            val duplicate = h.click(ground)
            verify(exactly = 1) { h.host.selectGround(h.player, id, CivsGroundTarget(ground.world.uid, CivPosition(2, 64, 2))) }
            for (event in listOf(offhand, first, duplicate)) verify(exactly = 1) { event.isCancelled = true }
            spawned.size shouldBe 12
            (spawned.minOf { it.y } < 63.0 && spawned.maxOf { it.y } < 64.1) shouldBe true
            ground.type shouldBe Material.GRASS_BLOCK
            grass.type shouldBe Material.SHORT_GRASS
            h.selection = null; h.preview = h.initialPreview
            pending.complete(Unit); h.paper.performTicks(2)
            h.controls.frame(h.player)!!.layout.buttons.first().action shouldBe CivsPlacementAction.CONFIRM
            verify(exactly = 0) { h.ui.open(any()); h.host.confirmPreview(any()) }
        }
    }

    test("selection rejection is visible in the action bar and the next click retries without a dialog") {
        controlsScenario { h ->
            h.startSelection()
            val selection = h.selection
            val ground = h.player.world.getBlockAt(0, 63, 0).also { it.type = Material.GRASS_BLOCK }
            every { h.host.selectGround(any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection("look-at-ground"))
            h.click(ground); h.paper.performTicks(2)
            val feedback = generateSequence { h.player.nextActionBar() }.first { it.color() == TextColor.color(0xFF6B61) }
            feedback.color() shouldBe TextColor.color(0xFF6B61)
            plainText(feedback) shouldContain "solid ground"
            h.selection shouldBe selection
            every { h.host.selectGround(any(), any(), any()) } answers {
                h.selection = null; h.preview = h.initialPreview
                CompletableFuture.completedFuture(Unit)
            }
            h.click(ground); h.paper.performTicks(2)
            verify(exactly = 2) { h.host.selectGround(any(), any(), any()) }
            verify(exactly = 0) { h.ui.open(any()) }
            h.controls.frame(h.player)!!.context.moving shouldBe false
        }
    }

    test("selection instructions refresh while stationary and cancellation removes its private marker") {
        controlsScenario { h ->
            h.player.teleport(Location(h.player.world, 0.5, 66.0, 0.5, 0f, 90f))
            val ground = h.player.world.getBlockAt(0, 63, 0).also { it.type = Material.GRASS_BLOCK }
            h.aimedBlock = ground
            h.startSelection()
            h.paper.performTicks(1)
            plainText(requireNotNull(h.player.nextActionBar())) shouldContain "Right-click barrel"
            h.paper.performTicks(21)
            plainText(requireNotNull(h.player.nextActionBar())) shouldContain "Right-click barrel"
            verify(exactly = 12) { h.displays.spawnBlock(any(), any()) }
            every { h.host.cancelSelection(h.player, "player-cancelled") } answers { h.selection = null; Unit }
            h.player.isSneaking = true
            h.click(ground)
            verify(exactly = 1) { h.host.cancelSelection(h.player, "player-cancelled") }
            verify(exactly = 0) { h.host.selectGround(any(), any(), any()) }
            h.controls.frame(h.player) shouldBe null
            ground.type shouldBe Material.GRASS_BLOCK
        }
    }

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
            every { h.host.movePreview(any(), any()) } answers {
                h.preview = h.preview!!.copy(id = UUID.randomUUID())
                CompletableFuture.completedFuture(Unit)
            }
            h.controls.activate(h.player, h.controls.frame(h.player)!!.context, CivsPlacementAction.MOVE)
            val event = mockk<PlayerInteractEvent>(relaxed = true)
            every { event.player } returns h.player
            every { event.action } returns Action.RIGHT_CLICK_BLOCK
            val ground = h.player.world.getBlockAt(2, 63, 2).also { it.type = Material.GRASS_BLOCK }
            every { event.clickedBlock } returns ground
            every { event.hand } returns EquipmentSlot.OFF_HAND
            h.controls.interact(event)
            verify(exactly = 0) { h.host.movePreview(any(), any()) }
            every { event.hand } returns EquipmentSlot.HAND
            h.controls.interact(event)
            h.paper.performTicks(2)
            verify(exactly = 2) { event.isCancelled = true }
            verify(exactly = 1) { h.host.movePreview(h.player, CivsGroundTarget(ground.world.uid, CivPosition(2, 63, 2))) }
            verify(exactly = 0) { h.host.movePreview(any()) }
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
    // MockBukkit deliberately leaves native ray tracing unimplemented; keep the sampled aim explicit.
    val player = object : PlayerMock(paper.server, "Mayor") {
        override fun rayTraceBlocks(maxDistance: Double, fluidCollisionMode: FluidCollisionMode): RayTraceResult? =
            aimedBlock?.let { RayTraceResult(Vector(it.x + 0.5, it.y + 1.0, it.z + 0.5), it, BlockFace.UP) }
    }.also { paper.server.addPlayer(it); it.isOp = true }
    val host = mockk<CivsUiHost>(relaxed = true)
    val ui = mockk<CivsUi>(relaxed = true)
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val panels = mockk<PaperActionPanels<CivsPlacementContext, CivsPlacementAction>>(relaxed = true)
    var dialog = false
    var aimedBlock: Block? = null
    var selection: CivsSelectionView? = null
    var preview: CivsPreviewView? = CivsPreviewView(BuildingType.TOWN_HALL, 1, "nordic", "Oak Town",
        CivPosition(0, 63, 0), 0, 12, 10, 12, Stock(), 400, UUID.randomUUID(), player.world.uid,
        CivPosition(0, 64, 0), CivBox(CivPosition(-6, 63, -12), CivPosition(5, 72, -1)), true, false)
    val initialPreview = preview
    val controls: CivsPlacementControls
    init {
        player.teleport(org.bukkit.Location(player.world, 0.5, 64.0, 4.5))
        every { host.ready() } returns true
        every { host.preview(player) } answers { preview }
        every { host.selection(player) } answers { selection }
        every { ui.showingDialog(player) } answers { dialog }
        every { panels.intercept(any()) } returns false
        controls = CivsPlacementControls(plugin, host, ui, tasks, CivsText.fromYaml(civsLocaleSource()), displays, panels)
    }
    fun startSelection() {
        preview = null
        selection = CivsSelectionView(UUID.randomUUID(), player.world.uid, BuildingType.TOWN_HALL,
            System.currentTimeMillis() + 120_000, false)
        controls.frame(player) shouldBe null
    }
    fun click(block: Block?, hand: EquipmentSlot = EquipmentSlot.HAND): PlayerInteractEvent =
        mockk<PlayerInteractEvent>(relaxed = true).also { event ->
            every { event.player } returns player
            every { event.action } returns if (block == null) Action.RIGHT_CLICK_AIR else Action.RIGHT_CLICK_BLOCK
            every { event.clickedBlock } returns block
            every { event.hand } returns hand
            controls.interact(event)
        }
    override fun close() { controls.close(); tasks.close() }
}
