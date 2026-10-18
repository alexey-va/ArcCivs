package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.spyk
import io.mockk.just
import io.mockk.Runs
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.player.PlayerChangedWorldEvent
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.network.BackendServerId
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CompletableFuture

class CivsUiTest : FunSpec({
    test("source callbacks record accepted and ignored clicks with a stable visit id") {
        withUi { h ->
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            mockkObject(CivsDiagnostics)
            every { CivsDiagnostics.event(any(), *anyVararg()) } answers {
                events += firstArg<String>() to secondArg<Array<Pair<String, Any?>>>().toMap()
                Unit
            }
            try {
                every { h.host.beginFoundingSelection(any(), any(), any()) } returns CompletableFuture<Unit>()
                h.open(); h.click("found"); h.click("style_0")
                val form = h.screen
                h.click("prepare", mapOf("city_name" to "Oak Town"))
                h.click("prepare", mapOf("city_name" to "Second"), screen = form)
                val clicks = events.filter { it.first == "ui_click" && it.second["screen"] == "civs.name" }.map { it.second }
                clicks.map { it["outcome"] } shouldBe listOf("accepted", "ignored")
                clicks.map { it["visit"] }.distinct().size shouldBe 1
                clicks.last()["reason"] shouldBe "already-consumed"
                clicks.first()["city_name"] shouldBe "Oak Town"
                clicks.first()["player"] shouldBe h.player.uniqueId
            } finally { unmockkObject(CivsDiagnostics) }
        }
    }

    test("a remote town has a read-only overview and a backend request without local mutation controls") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val remote = CivsNetworkCity(BackendServerId.of("survival"), settlement(h.player), 1000)
            every { h.host.remoteCity(h.player) } returns remote
            every { h.host.transferHome(h.player) } returns CompletableFuture.completedFuture(Unit)
            h.open()
            h.screen.id shouldBe "civs.remote"
            h.body() shouldContain "Ваш город находится на сервере survival"
            h.screen.buttons.map { it.id.value } shouldBe listOf("transfer", "refresh")
            plainText(h.screen.buttons.first().label) shouldBe "На сервер survival ›"
            h.click("transfer")
            h.paper.performTicks(2)
            h.body() shouldContain "Запрос перехода на survival отправлен"
            h.click("refresh")
            h.paper.performTicks(2)
            verify(exactly = 2) { h.host.refreshCity(h.player) }
            verify(exactly = 1) { h.host.transferHome(h.player) }
            verify(exactly = 0) { h.host.beginFoundingSelection(any(), any(), any()); h.host.recruit(any()) }
        }
    }

    test("failed network lookup is prominent and never offers founding from an unknown membership") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            every { h.host.refreshCity(any()) } returns CompletableFuture.failedFuture(CivsRejection("network-unavailable"))
            h.open()
            h.screen.id shouldBe "civs.network-error"
            plainText(h.screen.body.first().text) shouldBe "Не удалось загрузить город"
            h.screen.body.first().text.color() shouldBe TextColor.color(0xFF6B61)
            h.body() shouldContain "Не удалось получить данные города из сети"
            h.screen.buttons.map { it.id.value } shouldBe listOf("refresh")
            every { h.host.refreshCity(any()) } returns CompletableFuture.completedFuture(Unit)
            h.click("refresh")
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.welcome"
        }
    }

    test("startup storage failure displays a failure rather than endless loading") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            every { h.host.ready() } returns false
            every { h.host.unavailableReason() } returns "storage-unavailable"
            h.open()
            plainText(h.screen.body.first().text) shouldBe "Не удалось загрузить город"
            h.screen.body.first().text.color() shouldBe TextColor.color(0xFF6B61)
            h.body() shouldContain "Управление временно остановлено"
            verify(exactly = 0) { h.host.refreshCity(any()) }
        }
    }

    test("view-only backend does not expose founding and late lookup cannot reopen a closed screen") {
        withUi { h ->
            every { h.host.foundingAllowed() } returns false
            h.open()
            h.screen.buttons.map { it.id.value } shouldBe listOf("refresh")
            val pending = CompletableFuture<Unit>()
            every { h.host.refreshCity(any()) } returns pending
            h.click("refresh")
            val count = h.screens.size
            h.dismiss()
            pending.complete(Unit)
            h.paper.performTicks(2)
            h.screens.size shouldBe count
            h.ui.showingDialog(h.player) shouldBe false
        }
    }

    test("real Core dialog flow yields to world placement and details can return without reopening a modal") {
        withUi(realHistory = true) { h ->
            every { h.host.beginFoundingSelection(any(), any(), any()) } answers {
                h.selection = CivsSelectionView(UUID.randomUUID(), h.player.world.uid, BuildingType.TOWN_HALL,
                    System.currentTimeMillis() + 120_000, false)
                CompletableFuture.completedFuture(Unit)
            }
            h.open()
            h.click("found")
            h.click("style_0")
            h.click("prepare", mapOf("city_name" to "Oak Town"))
            h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            h.preview shouldBe null
            verify(exactly = 0) { h.host.selectGround(any(), any(), any()) }
            verify(exactly = 1) { h.player.closeDialog() }
            h.selection = null
            h.preview = preview()
            h.open()
            h.screen.id shouldBe "civs.preview"
            h.screen.buttons.map { it.id.value } shouldBe listOf("placement")
            h.click("placement")
            h.ui.showingDialog(h.player) shouldBe false
            verify(exactly = 1) { h.host.beginFoundingSelection(h.player, "Oak Town", "medieval-oak") }
            verify(exactly = 2) { h.player.closeDialog() }
        }
    }

    test("founding submits the literal name once then leaves confirmation to the world controls") {
        withUi { h ->
            val preparing = CompletableFuture<Unit>()
            every { h.host.beginFoundingSelection(any(), any(), any()) } returns preparing
            h.open()
            h.click("found")
            h.click("style_0")
            val submitted = h.screen
            h.click("prepare", mapOf("city_name" to "<red>Oak Town</red>"))
            h.click("prepare", mapOf("city_name" to "duplicate"), screen = submitted)
            verify(exactly = 1) { h.host.beginFoundingSelection(h.player, "<red>Oak Town</red>", "medieval-oak") }
            h.preview = preview()
            preparing.complete(Unit)
            h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            verify(exactly = 1) { h.runtime.close(h.player) }
            verify(exactly = 0) { h.host.confirmPreview(any()) }
            verify(exactly = 0) { h.host.cancelPreview(any()) }
        }
    }

    test("a rejected founding request preserves the name and the real Core form can retry") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            every { h.host.beginFoundingSelection(any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection("wrong-world", mapOf(
                    "world" to "world", "worlds" to "survival, vanilla",
                )))
            h.open()
            h.click("found")
            h.click("style_0")
            plainText(h.screen.buttons.single().label) shouldBe "Выбрать место ›"
            repeat(2) {
                h.click("prepare", mapOf("city_name" to "123123"))
                h.paper.performTicks(2)
                h.screen.id shouldBe "civs.name"
                h.screen.inputs.single().initial shouldBe "123123"
                plainText(h.screen.body[0].text) shouldBe "Не удалось выбрать место"
                h.screen.body[0].text.color() shouldBe TextColor.color(0xFF6B61)
                h.screen.body[0].text.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
                plainText(h.screen.body[1].text) shouldBe "В мире world нельзя размещать городские постройки.\n" +
                    "Разрешённые миры: survival, vanilla.\nПерейдите в один из них и повторите выбор места."
                h.screen.body[1].text.color() shouldBe CivsText.BODY
                plainText(h.screen.buttons.single().label) shouldBe "Повторить выбор места ›"
            }
            verify(exactly = 0) { h.player.sendMessage(any<Component>()) }
            verify(exactly = 2) { h.host.beginFoundingSelection(h.player, "123123", "medieval-oak") }
            every { h.host.beginFoundingSelection(any(), any(), any()) } answers {
                h.preview = preview().copy(cityName = secondArg())
                CompletableFuture.completedFuture(Unit)
            }
            h.click("prepare", mapOf("city_name" to "123123"))
            h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            verify(exactly = 3) { h.host.beginFoundingSelection(h.player, "123123", "medieval-oak") }
            verify(exactly = 1) { h.player.closeDialog() }
        }
    }

    test("selection rejection and empty names use the same prominent inline failure state") {
        withUi(realHistory = true) { h ->
            val preparing = CompletableFuture<Unit>()
            every { h.host.beginFoundingSelection(any(), any(), any()) } returns preparing
            h.open(); h.click("found"); h.click("style_0")
            h.click("prepare", mapOf("city_name" to "Oak Town"))
            h.screen.id shouldBe "civs.name"
            preparing.completeExceptionally(CivsRejection("invalid-style"))
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.name"
            h.screen.inputs.single().initial shouldBe "Oak Town"
            plainText(h.screen.body[0].text) shouldBe "Could not choose a location"
            h.screen.body[0].text.color() shouldBe TextColor.color(0xFF6B61)
            plainText(h.screen.body[1].text) shouldBe "This style is no longer available. Choose a style again."
            h.click("prepare", mapOf("city_name" to "   "))
            h.screen.inputs.single().initial shouldBe ""
            plainText(h.screen.body[0].text) shouldBe "Could not choose a location"
            h.screen.body[0].text.color() shouldBe TextColor.color(0xFF6B61)
            plainText(h.screen.body[1].text) shouldBe "Enter a nonempty name up to 32 characters without control characters."
            verify(exactly = 1) { h.host.beginFoundingSelection(any(), any(), any()) }
            verify(exactly = 0) { h.player.closeDialog() }
            verify(exactly = 0) { h.player.sendMessage(any<Component>()) }
        }
    }

    test("an async preview cannot reopen a dismissed flow or an old lifecycle") {
        withUi { h ->
            val first = CompletableFuture<Unit>()
            every { h.host.beginFoundingSelection(any(), any(), any()) } returns first
            h.open(); h.click("found"); h.click("style_0")
            h.click("prepare", mapOf("city_name" to "Oak Town"))
            val count = h.screens.size
            h.dismiss()
            verify(exactly = 1) { h.host.cancelSelection(h.player, "dialog-closed") }
            h.preview = preview()
            first.complete(Unit)
            h.paper.performTicks(2)
            h.screens.size shouldBe count

            h.preview = null
            val second = CompletableFuture<Unit>()
            every { h.host.beginFoundingSelection(any(), any(), any()) } returns second
            h.open(); h.click("found"); h.click("style_0")
            h.click("prepare", mapOf("city_name" to "New Town"))
            val nextCount = h.screens.size
            h.tasks.restart()
            h.preview = preview()
            second.complete(Unit)
            h.paper.performTicks(2)
            h.screens.size shouldBe nextCount
        }
    }

    test("assignment and removal use persisted state while Citizens still reports the previous job") {
        withUi { h ->
            h.city = settlement(h.player)
            val worker = h.city!!.workers.last()
            val forester = h.city!!.buildings.last()
            every { h.host.workerStatus(worker.id) } returns "workers.status.unassigned"
            every { h.host.assign(any(), any(), any()) } answers {
                h.city = CivsRules.assign(h.city!!, h.player.uniqueId, secondArg(), thirdArg(), 0)
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openWorker(h.player, h.city!!.id, worker.id)
            h.click("assign")
            val choices = h.screen
            h.click("assign_0")
            h.click("assign_0", screen = choices)
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.worker"
            h.body() shouldContain "Forester"
            h.body() shouldContain "Waiting for work"
            h.body().contains("No workplace assigned") shouldBe false
            verify(exactly = 1) { h.host.assign(h.player, worker.id, forester.id) }

            every { h.host.workerStatus(worker.id) } returns "workers.status.working"
            h.click("unassign")
            h.paper.performTicks(2)
            h.body() shouldContain "No workplace assigned"
            h.body().contains("Working") shouldBe false
            verify(exactly = 1) { h.host.assign(h.player, worker.id, null) }
            verify(exactly = 0) { h.runtime.close(h.player) }
        }
    }

    test("worker list uses the same assignment truth while current assigned runtime states remain visible") {
        withUi { h ->
            h.city = settlement(h.player)
            val worker = h.city!!.workers.last()
            h.city = CivsRules.assign(h.city!!, h.player.uniqueId, worker.id, h.city!!.buildings.last().id, 0)
            for ((status, label) in listOf(
                "no-assignment" to "Waiting for work",
                "sleeping" to "Settlement is sleeping",
                "route-unreachable" to "No accessible route",
                "working" to "Working",
            )) {
                every { h.host.workerStatus(worker.id) } returns "workers.status.$status"
                h.open()
                h.click("workers")
                plainText(h.screen.buttons.first { it.id.value == "worker_3" }.tooltip) shouldBe label
                h.click("worker_3")
                h.body() shouldContain label
            }
        }
    }

    test("resource shortage explains the block and storage offers no export action") {
        withUi { h ->
            h.city = settlement(h.player).copy(stock = Stock())
            h.open(); h.click("stock")
            h.screen.buttons.map { it.id.value } shouldBe listOf("refresh")
            h.body() shouldContain "cannot be withdrawn"
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_0")
            h.screen.buttons.map { it.id.value } shouldNotContain "prepare"
            h.body() shouldContain "does not have enough resources"
        }
    }

    test("new building starts ground selection and reopening the menu cancels that selection") {
        withUi(realHistory = true) { h ->
            h.city = settlement(h.player)
            every { h.host.beginBuildingSelection(any(), any()) } answers {
                h.selection = CivsSelectionView(UUID.randomUUID(), h.player.world.uid, secondArg(),
                    System.currentTimeMillis() + 120_000, false)
                CompletableFuture.completedFuture(Unit)
            }
            every { h.host.cancelSelection(h.player, "menu-reopened") } answers { h.selection = null; Unit }
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_0")
            h.click("prepare"); h.paper.performTicks(2)
            h.selection shouldNotBe null
            h.ui.showingDialog(h.player) shouldBe false
            h.preview shouldBe null
            verify(exactly = 1) { h.host.beginBuildingSelection(h.player, any()) }
            verify(exactly = 0) { h.host.selectGround(any(), any(), any()) }
            h.open()
            h.selection shouldBe null
            h.screen.id shouldBe "civs.overview"
            verify(exactly = 2) { h.host.cancelSelection(h.player, "menu-reopened") }
        }
    }

    test("management members cannot edit membership and upgrade previews cannot move") {
        withUi { h ->
            h.city = settlement(h.player).copy(owner = UUID.randomUUID(), members = setOf(h.player.uniqueId))
            h.open(); h.click("members")
            h.screen.buttons.map { it.id.value } shouldNotContain "add"
            h.preview = preview().copy(level = 2)
            h.open()
            h.screen.buttons.map { it.id.value } shouldBe listOf("placement")
        }
    }

    test("returning from preview details closes the screen while retaining the host preview") {
        withUi { h ->
            h.preview = preview()
            h.open(); h.click("placement")
            verify(exactly = 1) { h.runtime.close(h.player) }
            verify(exactly = 0) { h.host.cancelPreview(any()) }
            h.preview shouldBe preview()
        }
    }

    test("a stale details screen cannot mutate an expired placement") {
        withUi { h ->
            h.preview = preview()
            h.open()
            h.preview = null
            h.click("placement")
            h.ui.showingDialog(h.player) shouldBe false
            verify(exactly = 0) { h.host.confirmPreview(any()) }
            verify(exactly = 0) { h.host.rotatePreview(any(), any()) }
            verify(exactly = 0) { h.host.movePreview(any()) }
        }
    }

    test("known worker pauses and safe failures are translated without exposing raw diagnostics") {
        withUi { h ->
            h.city = settlement(h.player)
            every { h.host.workerStatus(any()) } returns "workers.status.route-unreachable"
            h.ui.openWorker(h.player, h.city!!.id, h.city!!.workers.first().id)
            h.body() shouldContain "No accessible route"
            val text = CivsText.fromYaml(civsLocaleSource())
            plainText(text.failure(h.player, IllegalStateException("private/internal/path"))) shouldBe
                "The action did not complete. Refresh this screen and try again."
        }
    }

    test("private preview rendering shares a per tick budget and eventually includes every block") {
        withPreview { h ->
            val second = h.paper.addPlayer("Neighbour")
            val building = settlement(h.player).buildings.first()
            h.renderer.show(h.player, building, previewBlocks(240))
            h.renderer.show(second, building, previewBlocks(240))
            h.paper.performTicks(1)
            h.handles.size shouldBe 128
            h.handles.forEach { handle ->
                verify(exactly = 1) { handle.isVisibleByDefault = false }
                verify(exactly = 1) { handle.showTo(any<Player>()) }
            }
            h.paper.performTicks(4)
            h.handles.size shouldBe 2 * (240 + 28)
            h.locations.count { it.x == 239.0 && it.y == 64.0 && it.z == 0.0 } shouldBe 2
            h.renderer.clear(h.player.uniqueId)
            h.renderer.clear(second.uniqueId)
            h.handles.forEach { verify(exactly = 1) { it.remove() } }
            h.paper.performTicks(2)
            h.handles.size shouldBe 536
        }
    }

    test("replacement world change and shutdown remove completed and pending private scenes") {
        withPreview { h ->
            val building = settlement(h.player).buildings.first()
            h.renderer.show(h.player, building, previewBlocks(300))
            h.paper.performTicks(1)
            val first = h.handles.toList()
            h.renderer.show(h.player, building, previewBlocks(1))
            first.forEach { verify(exactly = 1) { it.remove() } }
            h.paper.performTicks(1)
            h.handles.size shouldBe 128 + 29
            h.renderer.onWorldChange(PlayerChangedWorldEvent(h.player, h.player.world))
            h.handles.forEach { verify(exactly = 1) { it.remove() } }

            h.renderer.show(h.player, building, previewBlocks(300))
            h.paper.performTicks(1)
            val beforeClose = h.handles.size
            h.renderer.close()
            h.paper.performTicks(3)
            h.handles.size shouldBe beforeClose
            h.handles.forEach { verify(exactly = 1) { it.remove() } }
            verify(exactly = 1) { h.displays.closeAsync() }
        }
    }

    test("normal preview cleanup yields through the aggregate native queue") {
        MockBukkitTestRuntime.open().use { paper ->
            val h = PreviewHarness(paper, nativeBudget = 8)
            try {
                h.renderer.show(h.player, settlement(h.player).buildings.first(), previewBlocks(100))
                paper.performTicks(1)
                h.remainingHandles.size shouldBe 128
                h.renderer.clear(h.player.uniqueId)
                h.remainingHandles.size shouldBe 128
                repeat(20) {
                    val before = h.remainingHandles.size
                    paper.performTicks(1)
                    (before - h.remainingHandles.size <= 8) shouldBe true
                }
                h.remainingHandles.size shouldBe 0
            } finally { h.renderer.close(); h.nativeWork?.close(); h.tasks.close() }
        }
    }

    test("preview retirement observes the actual host packet cleanup acknowledgement") {
        withPreview { h ->
            val terminal = java.util.concurrent.CompletableFuture<Void>()
            every { h.displays.closeAsync() } returns terminal
            h.renderer.show(h.player, settlement(h.player).buildings.first(), previewBlocks(100))
            h.paper.performTicks(1)
            h.renderer.close()
            h.renderer.drained().isDone shouldBe false
            h.renderer.close()
            verify(exactly = 1) { h.displays.closeAsync() }
            terminal.complete(null)
            h.renderer.drained().isDone shouldBe true
        }
    }

    test("oversized previews reject rather than truncate the building or replace the current scene") {
        withPreview { h ->
            val building = settlement(h.player).buildings.first()
            h.renderer.show(h.player, building, previewBlocks(1))
            h.paper.performTicks(1)
            shouldThrow<CivsRejection> {
                h.renderer.show(h.player, building, previewBlocks(6_001))
            }.reason shouldBe "preview-too-large"
            h.handles.size shouldBe 29
            h.handles.forEach { verify(exactly = 0) { it.remove() } }
            val cues = civsPreviewCues(building)
            cues.count { it.material == Material.ORANGE_CONCRETE } shouldBe 12
            cues.count { it.material == Material.LIME_CONCRETE } shouldBe 12
            cues.count { it.material == Material.CYAN_CONCRETE } shouldBe 4
            cues.filter { it.material == Material.CYAN_CONCRETE }.forEach {
                it.y shouldBe building.entrance.y + 0.025
            }
        }
    }
})

private fun withUi(realHistory: Boolean = false, action: (UiHarness) -> Unit) = MockBukkitTestRuntime.open().use { paper ->
    val harness = UiHarness(paper, realHistory)
    try { action(harness) } finally { harness.ui.close(); harness.tasks.close() }
}

private class UiHarness(val paper: MockBukkitTestRuntime, private val realHistory: Boolean) {
    val plugin = paper.createSimplePlugin("CivsUiTest")
    val player = spyk(paper.addPlayer("Mayor")).apply { setLocale(Locale.US) }
    val host = mockk<CivsUiHost>(relaxed = true)
    val runtime: PaperDialogRuntime
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val screens = mutableListOf<PaperDialogScreen>()
    var city: Settlement? = null
    var preview: CivsPreviewView? = null
    var selection: CivsSelectionView? = null
    private var dismissAction: () -> Unit = {}
    private lateinit var registration: Any
    val ui: CivsUi
    val screen: PaperDialogScreen get() = screens.last()

    init {
        every { player.closeDialog() } just Runs
        every { host.ready() } returns true
        every { host.unavailableReason() } returns null
        every { host.refreshCity(any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.remoteCity(any()) } returns null
        every { host.foundingAllowed() } returns true
        every { host.styles() } returns listOf("medieval-oak")
        every { host.city(any()) } answers { city }
        every { host.settlements() } answers { listOfNotNull(city) }
        every { host.preview(any()) } answers { preview }
        every { host.selection(any()) } answers { selection }
        every { host.workerStatus(any()) } returns "workers.status.sleeping"
        every { host.confirmPreview(any()) } answers {
            city = settlement(player)
            preview = null
            CompletableFuture.completedFuture(Unit)
        }
        if (realHistory) {
            // The same presenter seam used by ArcRanks tests retains real Core sessions and history.
            val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, shown, registered ->
                screens += shown
                registration = registered
            }
            val constructor = PaperDialogRuntime::class.java.declaredConstructors
                .first { it.parameterCount == 2 }.apply { isAccessible = true }
            runtime = constructor.newInstance(plugin, presenter) as PaperDialogRuntime
        } else {
            runtime = mockk(relaxed = true)
            every { runtime.open(any(), any(), any(), any(), any()) } answers {
                screens += secondArg<PaperDialogScreen>()
                dismissAction = arg(3)
                Unit
            }
            every { runtime.close(any()) } answers { dismissAction(); Unit }
            every { runtime.beginFlow(any()) } answers { dismissAction(); Unit }
        }
        ui = CivsUi(plugin, host, tasks, runtime, CivsText.fromYaml(civsLocaleSource()))
    }

    fun open() { ui.open(player); paper.performTicks(2) }
    fun dismiss() = dismissAction()
    fun body(): String = screen.body.joinToString("\n") { plainText(it.text) }
    fun click(id: String, values: Map<String, String> = emptyMap(), screen: PaperDialogScreen = this.screen) {
        if (realHistory) {
            val keyMethod = registration.javaClass.declaredMethods.first { it.name.startsWith("key") }
                .apply { isAccessible = true }
            val key = keyMethod.invoke(registration, id) as String
            val connection = mockk<PlayerGameConnection> { every { player } returns this@UiHarness.player }
            val response = mockk<DialogResponseView> {
                every { getText(any()) } answers { values[firstArg<String>()] }
                every { getFloat(any()) } returns null
            }
            runtime.onCustomClick(mockk {
                every { commonConnection } returns connection
                every { identifier } returns Key.key(key)
                every { dialogResponseView } returns response
            })
            return
        }
        val context = mockk<PaperDialogClickContext> {
            every { player } returns this@UiHarness.player
            every { text(any()) } returns null
        }
        values.forEach { (key, value) -> every { context.text(ru.arc.paper.menu.PaperDialogInputId.of(key)) } returns value }
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    }
}

private fun preview() = CivsPreviewView(BuildingType.TOWN_HALL, 1, "medieval-oak", "Oak Town",
    CivPosition(0, 64, 0), 0, 12, 10, 12, Stock(), 400, UUID(0, 1), UUID(0, 2),
    CivPosition(0, 65, 0), CivBox(CivPosition(-6, 64, -12), CivPosition(5, 73, -1)), true, false)

private fun settlement(player: Player): Settlement {
    fun building(type: BuildingType, x: Int, level: Int = 1) = CivBuilding(UUID.randomUUID(), type, level,
        CivPosition(x, 64, 0), 0, "medieval-oak/${type.name.lowercase()}",
        CivBox(CivPosition(x, 64, 0), CivPosition(x + 4, 70, 4)), CivPosition(x + 2, 64, -1), BuildingPhase.ACTIVE)
    val hall = building(BuildingType.TOWN_HALL, 0, 2)
    val house = building(BuildingType.HOUSE, 10)
    val forest = building(BuildingType.FORESTER, 20)
    return Settlement(UUID.randomUUID(), player.uniqueId, "Oak Town", player.world.uid, CivPosition(0, 64, 0), 64,
        "medieval-oak", Stock(96, 96, 24), listOf(hall, house, forest),
        listOf(CivWorker(UUID.randomUUID(), "Builder", hall.id, nextDeliveryAt = 10_000),
            CivWorker(UUID.randomUUID(), "Resident A", null), CivWorker(UUID.randomUUID(), "Resident B", null),
            CivWorker(UUID.randomUUID(), "Forester", null)))
}

private fun withPreview(action: (PreviewHarness) -> Unit) = MockBukkitTestRuntime.open().use { paper ->
    val harness = PreviewHarness(paper)
    try { action(harness) } finally { harness.renderer.close(); harness.tasks.close() }
}

private class PreviewHarness(val paper: MockBukkitTestRuntime, nativeBudget: Int? = null) {
    val plugin = paper.createSimplePlugin("CivsPreviewTest")
    val player = paper.addPlayer("Mayor")
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val nativeWork = nativeBudget?.let { ru.arc.core.BudgetedTaskQueue(tasks, maximumSteps = it, nanoTime = { 0 }) }
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val handles = mutableListOf<PacketBlockDisplay>()
    val remainingHandles = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PacketBlockDisplay, Boolean>())
    val locations = mutableListOf<Location>()
    val renderer: CivsPreviewRenderer

    init {
        every { displays.spawnBlock(any(), any()) } answers {
            locations += firstArg<Location>().clone()
            mockk<PacketBlockDisplay>(relaxed = true).also { handle ->
                handles += handle
                remainingHandles += handle
                every { handle.remove() } answers { remainingHandles.remove(handle); Unit }
            }
        }
        // The real typed owner closes every remaining handle; the fake must preserve that contract.
        every { displays.closeAsync() } answers {
            remainingHandles.toList().forEach { it.remove() }
            java.util.concurrent.CompletableFuture.completedFuture(null)
        }
        renderer = CivsPreviewRenderer(plugin, tasks, displays, nativeWork)
    }
}

private fun previewBlocks(count: Int): List<ArcSchematicBlock> = List(count) { index ->
    ArcSchematicBlock(ArcSchematicPoint(index, 64, 0), "minecraft:stone", false, false)
}
