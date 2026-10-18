package ru.arc.civs

import com.google.gson.GsonBuilder
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
import io.mockk.verifyOrder
import io.mockk.spyk
import io.mockk.just
import io.mockk.Runs
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
            h.screen.buttons.map { it.id.value } shouldBe listOf("transfer")
            plainText(h.screen.buttons.first().label) shouldBe "На сервер survival ›"
            h.click("transfer")
            h.paper.performTicks(2)
            h.body() shouldContain "Запрос перехода на survival отправлен"
            h.open()
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
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            every { h.host.refreshCity(any()) } returns CompletableFuture.completedFuture(Unit)
            h.open()
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
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            val pending = CompletableFuture<Unit>()
            every { h.host.refreshCity(any()) } returns pending
            h.open()
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
                h.screen.body[1].text.color() shouldBe CivsText.ERROR
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
            h.body().contains("Walking around · unassigned") shouldBe false
            verify(exactly = 1) { h.host.assign(h.player, worker.id, forester.id) }

            every { h.host.workerStatus(worker.id) } returns "workers.status.working"
            h.click("unassign")
            h.paper.performTicks(2)
            h.body() shouldContain "Walking around · unassigned"
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

    test("an empty warehouse does not block marking another building site") {
        withUi { h ->
            h.city = settlement(h.player).copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            h.open(); h.click("management"); h.click("stock")
            h.body() shouldContain "Each building has its own warehouse"
            h.click("warehouse_0")
            h.body() shouldContain "Nothing stored yet"
            h.screen.buttons.map { it.id.value } shouldBe listOf("intake", "deposit")
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_0")
            h.screen.buttons.map { it.id.value } shouldBe listOf("prepare")
            h.body() shouldContain "create a control barrel and temporary fence"
            verify(exactly = 0) { h.host.startBuilding(any(), any()); h.host.availableMaterials(any()) }
        }
    }

    test("preview coordinates use the authored control position and separate entrance instead of the paste origin") {
        withUi { h ->
            h.open(); h.click("found"); h.click("style_0")
            h.body() shouldContain "right-click a block"
            h.preview = preview().copy(anchor = CivPosition(20, 60, 30), entrance = CivPosition(8, 66, 9), control = CivPosition(12, 68, 14))
            h.open()
            h.body() shouldContain "Control block"
            h.body() shouldContain "12, 68, 14"
            h.body() shouldContain "Entrance"
            h.body() shouldContain "8, 66, 9"
            h.body() shouldContain "green frame marks the future control barrel"
            h.body().contains("20, 60, 30") shouldBe false
            h.preview = h.preview!!.copy(movable = false)
            h.open()
            h.body() shouldContain "12, 68, 14"
            h.body().contains("20, 60, 30") shouldBe false
            h.body() shouldContain "Confirm to start the upgrade"
        }
    }

    test("legacy control relocation shows the exact destination and submits only the selected building once") {
        withUi { h ->
            val base = settlement(h.player)
            val old = base.buildings.last().let { it.copy(control = it.anchor) }
            val target = CivPosition(22, 66, 2)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == old.id) old else it })
            every { h.host.buildingControl(h.player, old.id) } returns CompletableFuture.completedFuture(target)
            every { h.host.relocateControl(h.player, old.id, target) } answers {
                h.city = h.city!!.copy(buildings = h.city!!.buildings.map { if (it.id == old.id) it.copy(control = target) else it })
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openBuilding(h.player, base.id, old.id)
            h.click("relocate_control"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.control-relocation"
            h.body() shouldContain "20, 64, 0"
            h.body() shouldContain "22, 66, 2"
            h.body() shouldContain "create a barrel at the new coordinates"
            h.body() shouldContain "remains a linked storage container"
            val page = h.screen
            h.click("relocate"); h.click("relocate", screen = page); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.relocateControl(h.player, old.id, target) }
            h.screen.id shouldBe "civs.building"
            h.body() shouldContain "22, 66, 2"
            h.body() shouldContain "Control block relocated"
            h.screen.buttons.map { it.id.value } shouldNotContain "relocate_control"
            verify(exactly = 0) { h.host.depositMaterials(any(), any()); h.host.withdrawMaterial(any(), any(), any()) }
        }
    }

    test("relocation errors retain the destination and busy or constructing settlements cannot confirm") {
        withUi { h ->
            val base = settlement(h.player)
            val old = base.buildings.last().let { it.copy(control = it.anchor) }
            val target = CivPosition(22, 66, 2)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == old.id) old else it })
            every { h.host.buildingControl(h.player, old.id) } returns CompletableFuture.completedFuture(target)
            every { h.host.relocateControl(h.player, old.id, target) } returns CompletableFuture.failedFuture(
                CivsRejection("control-position-missing", mapOf("x" to "22", "y" to "66", "z" to "2")))
            h.ui.openBuilding(h.player, base.id, old.id)
            h.click("relocate_control"); h.paper.performTicks(2)
            h.click("relocate"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.control-relocation"
            h.body() shouldContain "Place a normal barrel at the control block location: 22, 66, 2"
            val busyLookup = CompletableFuture<CivPosition>()
            every { h.host.buildingControl(h.player, old.id) } returns busyLookup
            h.ui.openBuilding(h.player, base.id, old.id); h.click("relocate_control")
            every { h.host.cityBusy(h.player) } returns true
            busyLookup.complete(target); h.paper.performTicks(2)
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            h.body() shouldContain "Another settlement action"
            every { h.host.cityBusy(h.player) } returns false
            val constructionLookup = CompletableFuture<CivPosition>()
            every { h.host.buildingControl(h.player, old.id) } returns constructionLookup
            h.ui.openBuilding(h.player, base.id, old.id); h.click("relocate_control")
            h.city = h.city!!.copy(construction = CivConstruction(UUID.randomUUID(), base.buildings.first().id,
                3, 100, 20, ConstructionPhase.CONSTRUCTING, Stock()))
            constructionLookup.complete(target); h.paper.performTicks(2)
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            h.body() shouldContain "Construction is already in progress"
            h.ui.openBuilding(h.player, base.id, old.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "relocate_control"
        }
    }

    test("only completed legacy buildings offer relocation and a closed target lookup cannot reopen") {
        withUi { h ->
            val base = settlement(h.player)
            val old = base.buildings.last().copy(control = null)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == old.id) old.copy(phase = BuildingPhase.SITE) else it })
            h.ui.openBuilding(h.player, base.id, old.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "relocate_control"
            h.city = base.copy(buildings = base.buildings.map { if (it.id == old.id) old else it })
            val pending = CompletableFuture<CivPosition>()
            every { h.host.buildingControl(h.player, old.id) } returns pending
            h.ui.openBuilding(h.player, base.id, old.id)
            h.click("relocate_control")
            val count = h.screens.size
            h.dismiss()
            pending.complete(CivPosition(22, 66, 2)); h.paper.performTicks(2)
            h.screens.size shouldBe count
            h.ui.showingDialog(h.player) shouldBe false
            verify(exactly = 0) { h.host.relocateControl(any(), any(), any()) }
        }
    }

    test("site material estimate is not a debit gate and starting uses the selected building once") {
        withUi { h ->
            val base = settlement(h.player)
            val site = base.buildings.first { it.type == BuildingType.BUILDER_HUT }.copy(phase = BuildingPhase.SITE)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == site.id) site else it })
            every { h.host.buildingMaterials(h.player, site.id) } returns CompletableFuture.completedFuture(mapOf("OAK_LOG" to 900L))
            every { h.host.availableMaterials(h.player) } returns emptyMap()
            every { h.host.startBuilding(h.player, site.id) } returns CompletableFuture.completedFuture(Unit)
            h.ui.openBuilding(h.player, base.id, site.id)
            h.screen.id shouldBe "civs.building"
            h.body() shouldContain "Site marked out"
            h.click("materials"); h.paper.performTicks(2)
            h.body() shouldContain "upper estimates"
            h.body() shouldContain "0 / up to 900"
            val before = h.screen
            h.click("start"); h.click("start", screen = before)
            h.paper.performTicks(2)
            verify(exactly = 1) { h.host.startBuilding(h.player, site.id) }
        }
    }

    test("active construction shows remaining materials and waiting resumes without a retry control") {
        withUi { h ->
            val base = settlement(h.player)
            val building = base.buildings.first()
            val order = CivConstruction(UUID.randomUUID(), building.id, 3, 100, 40,
                ConstructionPhase.WAITING_MATERIALS, Stock(), materials = mapOf("COBBLESTONE" to 19L),
                payment = CivConstructionPayment(awaiting = mapOf("COBBLESTONE" to 3L)))
            h.city = base.copy(construction = order)
            every { h.host.availableMaterials(h.player) } returns mapOf("COBBLESTONE" to 2L)
            h.open()
            h.body() shouldContain "refill storage so the builder can continue"
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.body() shouldContain "Waiting for materials"
            h.body() shouldContain "2 / 19"
            h.body() shouldContain "continue automatically"
            h.body().contains("up to") shouldBe false
            h.screen.buttons shouldBe emptyList()
            h.city = h.city!!.copy(construction = order.copy(phase = ConstructionPhase.CONSTRUCTING,
                materials = mapOf("COBBLESTONE" to 3L)))
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.body() shouldContain "2 / 3"
            h.body().contains("Waiting for materials") shouldBe false
            h.city = h.city!!.copy(construction = order.copy(payment = null))
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.body() shouldContain "already paid"
            verify(exactly = 0) { h.host.buildingMaterials(any(), any()); h.host.startBuilding(any(), any()); h.host.retryConstruction(any(), any()) }
        }
    }

    test("construction materials use a framed localized table and preserve exact counts across pages") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val site = base.buildings.first().copy(phase = BuildingPhase.SITE)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == site.id) site else it })
            every { h.host.buildingMaterials(h.player, site.id) } returns CompletableFuture.completedFuture(materialEstimate())
            every { h.host.availableMaterials(h.player) } returns mapOf("COBBLESTONE" to 5L, "SPRUCE_PLANKS" to 9_000_001L)
            h.ui.openBuilding(h.player, base.id, site.id)
            plainText(h.screen.buttons.single { it.id.value == "materials" }.label) shouldBe "Строительство ›"
            plainText(h.screen.buttons.single { it.id.value == "warehouse" }.label) shouldBe "Склад ›"
            h.click("materials"); h.paper.performTicks(2)
            plainText(h.screen.title) shouldBe "Строительство"
            h.body() shouldContain "Предмет"
            h.body() shouldContain "Нужно"
            h.body() shouldContain "Булыжные ступеньки"
            h.body() shouldContain "5 / до 73"
            h.body() shouldContain "0 / до 516"
            h.body() shouldContain "\uE570"
            h.body().contains("Cobblestone") shouldBe false
            h.screen.buttons.map { it.id.value } shouldBe listOf("start", "next")
            h.click("next")
            h.body() shouldContain "Еловые доски"
            h.body() shouldContain "9000001"
            h.body() shouldContain "2000003"
            h.body() shouldContain "до"
            h.body().contains("Булыжник") shouldBe false
            h.screen.buttons.map { it.id.value } shouldBe listOf("start", "previous")
            h.click("previous")
            h.body() shouldContain "5 / до 73"
            verify(exactly = 1) { h.host.buildingMaterials(h.player, site.id) }
            verify(exactly = 0) { h.host.startBuilding(any(), any()) }
        }
    }

    test("closed material lookup cannot reopen a building menu") {
        withUi { h ->
            val base = settlement(h.player)
            val site = base.buildings.last().copy(phase = BuildingPhase.SITE)
            h.city = base.copy(buildings = base.buildings.dropLast(1) + site)
            val pending = CompletableFuture<Map<String, Long>>()
            every { h.host.buildingMaterials(h.player, site.id) } returns pending
            h.ui.openBuilding(h.player, base.id, site.id)
            h.click("materials")
            val count = h.screens.size
            h.dismiss()
            pending.complete(mapOf("OAK_LOG" to 10L)); h.paper.performTicks(2)
            h.screens.size shouldBe count
        }
    }

    test("warehouse pages isolate building stock from reserve and use level capacity") {
        withUi { h ->
            val base = settlement(h.player)
            val hall = base.buildings.first()
            val forest = base.buildings.last()
            val own = listOf("BIRCH_LOG", "BRICKS", "CARROT", "COBBLESTONE", "DIRT", "GLASS", "OAK_LOG", "POTATO", "SAND", "STONE")
                .associateWith { 10L }
            h.city = base.copy(inventory = CivInventory(CivStorageMode.VIRTUAL, mapOf("BREAD" to 12L),
                buildingMaterials = mapOf(hall.id.toString() to own, forest.id.toString() to mapOf("DIAMOND" to 99L))))
            h.ui.openBuilding(h.player, base.id, hall.id); h.click("warehouse")
            h.body() shouldContain "100 / 3456"
            h.body() shouldContain "0 · reserve 12"
            h.body().contains("99") shouldBe false
            h.screen.buttons.map { it.id.value } shouldNotContain "previous"
            h.click("next")
            h.screen.buttons.map { it.id.value }.contains("previous") shouldBe true
            h.screen.buttons.map { it.id.value } shouldNotContain "next"
            h.click("withdraw")
            h.screen.buttons.count { it.id.value.startsWith("withdraw_") } shouldBe 8
            h.click("next")
            h.screen.buttons.count { it.id.value.startsWith("withdraw_") } shouldBe 3
            val expected = (own.keys + "BREAD").sorted()[8]
            h.click("withdraw_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.withdrawMaterial(h.player, hall.id, expected) }
            verify(exactly = 0) { h.host.withdrawMaterial(h.player, forest.id, any()) }
        }
    }

    test("intake closes the dialog before opening native inventory and busy storage hides transfers") {
        withUi { h ->
            h.city = settlement(h.player).let { it.copy(inventory = it.inventory!!.copy(mode = CivStorageMode.VIRTUAL)) }
            val building = h.city!!.buildings.first()
            h.ui.openBuilding(h.player, h.city!!.id, building.id); h.click("warehouse"); h.click("intake")
            h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            verifyOrder { h.runtime.close(h.player); h.host.openBuildingInventory(h.player, building.id) }
            every { h.host.cityBusy(h.player) } returns true
            h.ui.openBuilding(h.player, h.city!!.id, building.id); h.click("warehouse")
            h.body() shouldContain "Another settlement action"
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
        }
    }

    test("physical building storage opens the central barrel directly from both entries") {
        withUi { h ->
            h.city = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
            val building = h.city!!.buildings.first()
            h.ui.openBuilding(h.player, h.city!!.id, building.id); h.click("warehouse")
            h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            verifyOrder { h.runtime.close(h.player); h.host.openBuildingInventory(h.player, building.id) }
            h.open(); h.click("management"); h.click("stock"); h.click("warehouse_0")
            h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            h.screens.any { it.id == "civs.warehouse" } shouldBe false
            verify(exactly = 2) { h.host.openBuildingInventory(h.player, building.id) }
            verify(exactly = 0) { h.host.depositMaterials(any(), any()); h.host.withdrawMaterial(any(), any(), any()) }
        }
    }

    test("a missing physical barrel returns to the building card and busy storage hides opening") {
        withUi { h ->
            h.city = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
            val building = h.city!!.buildings.first()
            every { h.host.openBuildingInventory(h.player, building.id) } returns CompletableFuture.failedFuture(CivsRejection("control-missing"))
            h.ui.openBuilding(h.player, h.city!!.id, building.id); h.click("warehouse")
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.building"
            h.body() shouldContain "The control barrel is missing"
            verify(exactly = 1) { h.host.openBuildingInventory(h.player, building.id) }
            every { h.host.cityBusy(h.player) } returns true
            h.ui.openBuilding(h.player, h.city!!.id, building.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "warehouse"
            h.body() shouldContain "Another settlement action"
            h.open(); h.click("management"); h.click("stock")
            h.screen.buttons.any { it.id.value.startsWith("warehouse_") } shouldBe false
        }
    }

    test("physical residual stock remains visible after partial transfer and clears only when delivered") {
        withUi { h ->
            val base = settlement(h.player)
            val building = base.buildings.first()
            h.city = base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL, mapOf("BREAD" to 12L),
                buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 64L))))
            every { h.host.materializeStorage(h.player) } answers {
                h.city = h.city!!.copy(inventory = CivInventory(CivStorageMode.PHYSICAL, mapOf("BREAD" to 4L)))
                CompletableFuture.completedFuture(Unit)
            }
            h.open(); h.click("management"); h.click("stock")
            h.body() shouldContain "76 items"
            h.body() shouldContain "Remaining stock is preserved"
            h.screen.buttons.any { it.id.value == "materialize" } shouldBe true
            h.click("materialize"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.stock"
            h.body() shouldContain "4 items"
            h.body() shouldContain "Remaining stock is preserved"
            every { h.host.cityBusy(h.player) } returns true
            h.open(); h.click("management"); h.click("stock")
            h.screen.buttons.map { it.id.value } shouldNotContain "materialize"
            every { h.host.cityBusy(h.player) } returns false
            h.city = h.city!!.copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
            h.open(); h.click("management"); h.click("stock")
            h.body().contains("Remaining stock is preserved") shouldBe false
            h.body().contains("4 items") shouldBe false
            h.screen.buttons.map { it.id.value } shouldNotContain "materialize"
            verify(exactly = 1) { h.host.materializeStorage(h.player) }
        }
    }

    test("a stale physical transfer reports the changed mode and returns to virtual storage") {
        withUi { h ->
            h.city = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.PHYSICAL, mapOf("BREAD" to 12L)))
            h.open(); h.click("management"); h.click("stock")
            h.city = h.city!!.let { it.copy(inventory = it.inventory!!.copy(mode = CivStorageMode.VIRTUAL)) }
            every { h.host.materializeStorage(h.player) } returns CompletableFuture.failedFuture(CivsRejection("invalid-storage-mode"))
            h.click("materialize"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.stock"
            h.body() shouldContain "The storage mode changed"
            h.screen.buttons.map { it.id.value } shouldNotContain "materialize"
            h.click("warehouse_0")
            h.screen.id shouldBe "civs.warehouse"
            verify(exactly = 0) { h.host.openBuildingInventory(any(), any()) }
        }
    }

    test("building worker selection reassigns the chosen resident to that exact building") {
        withUi { h ->
            h.city = settlement(h.player)
            val forest = h.city!!.buildings.last()
            val worker = h.city!!.workers.first()
            every { h.host.assign(any(), any(), any()) } answers {
                h.city = CivsRules.assign(h.city!!, h.player.uniqueId, secondArg(), thirdArg(), 0)
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openBuilding(h.player, h.city!!.id, forest.id); h.click("workers")
            h.screen.id shouldBe "civs.building-workers"
            h.click("worker_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.assign(h.player, worker.id, forest.id) }
            h.screen.buttons.count { it.id.value.startsWith("worker_") } shouldBe 1
            h.click("worker_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.assign(h.player, worker.id, null) }
        }
    }

    test("a full migrated town without a builder hut can assign its town hall builder") {
        withUi { h ->
            val base = settlement(h.player)
            val hall = base.buildings.first()
            val template = base.buildings.first { it.type == BuildingType.HOUSE }
            val positions = listOf(-50, -35, 35, 50).flatMap { x -> listOf(-50, -35, 35, 50).map { z -> x to z } }
            val houses = positions.take(13).map { (x, z) -> template.copy(id = UUID.randomUUID(), anchor = CivPosition(x, 64, z),
                bounds = CivBox(CivPosition(x, 64, z), CivPosition(x + 4, 70, z + 4)),
                entrance = CivPosition(x + 2, 64, z - 1), control = CivPosition(x + 2, 63, z - 1)) }
            h.city = base.copy(buildings = base.buildings.filter { it.type != BuildingType.BUILDER_HUT } + houses,
                workers = base.workers.map { it.copy(assignment = null) })
            every { h.host.assign(any(), any(), any()) } answers {
                h.city = CivsRules.assign(h.city!!, h.player.uniqueId, secondArg(), thirdArg(), 0)
                CompletableFuture.completedFuture(Unit)
            }
            h.open()
            h.body().contains("mark a builder hut") shouldBe false
            h.ui.openBuilding(h.player, base.id, hall.id)
            h.body() shouldContain "One resident can work as a builder at the town hall"
            h.screen.buttons.map { it.id.value }.contains("workers") shouldBe true
            val worker = h.city!!.workers[1]
            h.ui.openWorker(h.player, base.id, worker.id); h.click("assign"); h.click("assign_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.assign(h.player, worker.id, hall.id) }
        }
    }

    test("production selector follows the building options and displays durable selection") {
        withUi { h ->
            h.city = settlement(h.player)
            val forest = h.city!!.buildings.last()
            every { h.host.setProduction(any(), any(), any()) } answers {
                val product = thirdArg<String>()
                h.city = h.city!!.copy(buildings = h.city!!.buildings.map { if (it.id == forest.id) it.copy(production = product) else it })
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openBuilding(h.player, h.city!!.id, forest.id); h.click("production")
            h.screen.buttons.count { it.id.value.startsWith("production_") } shouldBe CivsProduction.options(forest.type).size
            h.click("production_1"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.setProduction(h.player, forest.id, "SPRUCE_LOG") }
            plainText(h.screen.buttons.first { it.id.value == "production_1" }.label).startsWith("✔") shouldBe true
        }
    }

    test("storage mode and container controls use the existing city APIs") {
        withUi { h ->
            h.city = settlement(h.player)
            val container = CivPosition(3, 64, 9)
            every { h.host.setStorageMode(any(), any()) } answers {
                h.city = h.city!!.copy(inventory = h.city!!.inventory!!.copy(mode = secondArg()))
                CompletableFuture.completedFuture(Unit)
            }
            every { h.host.registerContainer(h.player) } answers {
                h.city = h.city!!.copy(inventory = h.city!!.inventory!!.copy(containers = listOf(container)))
                CompletableFuture.completedFuture(Unit)
            }
            every { h.host.unregisterContainer(h.player, container) } returns CompletableFuture.completedFuture(Unit)
            h.open(); h.click("management"); h.click("stock"); h.click("mode"); h.click("mode_virtual"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.setStorageMode(h.player, CivStorageMode.VIRTUAL) }
            h.open(); h.click("management"); h.click("stock"); h.click("containers"); h.click("register"); h.paper.performTicks(2)
            h.click("container_0"); h.click("unregister"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.registerContainer(h.player); h.host.unregisterContainer(h.player, container) }
        }
    }

    test("arriving pioneers remain visible and an unassigned sleeping resident is not mislabeled") {
        withUi { h ->
            val base = settlement(h.player)
            h.city = base.copy(workers = emptyList(), immigration = CivImmigration(4, 30_000))
            h.open(); h.click("workers")
            h.body() shouldContain "30 seconds"
            h.screen.buttons.map { it.id.value } shouldNotContain "recruit"
            h.city = base
            val idle = base.workers[1]
            every { h.host.workerStatus(idle.id) } returns "workers.status.sleeping"
            h.ui.openWorker(h.player, base.id, idle.id)
            h.body() shouldContain "Settlement is sleeping"
            every { h.host.workerStatus(idle.id) } returns "workers.status.unassigned"
            h.ui.openWorker(h.player, base.id, idle.id)
            h.body() shouldContain "Walking around · unassigned"
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

    test("settlement management keeps existing submenus and returns through real Core history") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.city = settlement(h.player)
            h.open()
            h.screen.buttons.map { it.id.value } shouldBe listOf("management", "buildings", "workers", "guide")
            plainText(h.screen.buttons.first().label) shouldBe "Управление поселением ›"
            h.click("management")
            h.screen.id shouldBe "civs.management"
            plainText(h.screen.title) shouldBe "Управление поселением"
            h.screen.buttons.map { it.id.value } shouldBe listOf("stock", "upgrades", "members", "delete_city")
            h.screen.buttons.single { it.id.value == "delete_city" }.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(h.screen.buttons.last().label) shouldBe "Удалить поселение"
            for ((action, screen) in listOf("stock" to "civs.stock", "upgrades" to "civs.upgrades", "members" to "civs.members")) {
                h.click(action)
                h.screen.id shouldBe screen
                h.click("history")
                h.screen.id shouldBe "civs.management"
            }
            h.click("history")
            h.screen.id shouldBe "civs.overview"
        }
    }

    test("the guide keeps static instructions out of the overview and returns through Core history") {
        withUi(realHistory = true) { h ->
            h.city = settlement(h.player)
            h.open()
            h.body().contains("Cyan markers appear") shouldBe false
            h.body().contains("Without nearby players") shouldBe false
            h.click("guide")
            h.screen.id shouldBe "civs.guide"
            h.body() shouldContain "Cyan markers appear"
            h.body() shouldContain "Without nearby players"
            h.body() shouldContain "create a control barrel and temporary fence"
            h.body() shouldContain "Storage opens the central barrel"
            h.click("history")
            h.screen.id shouldBe "civs.overview"
            h.body().contains("Without nearby players") shouldBe false
        }
    }

    test("management members cannot edit membership and upgrade previews cannot move") {
        withUi { h ->
            h.city = settlement(h.player).copy(owner = UUID.randomUUID(), members = setOf(h.player.uniqueId))
            h.open()
            h.screen.buttons.map { it.id.value } shouldNotContain "delete_city"
            h.click("management")
            h.screen.buttons.map { it.id.value } shouldBe listOf("stock", "upgrades", "members")
            h.click("members")
            h.screen.buttons.map { it.id.value } shouldNotContain "add"
            h.preview = preview().copy(level = 2, movable = false)
            h.open()
            h.screen.buttons.map { it.id.value } shouldBe listOf("placement", "management")
        }
    }

    test("an existing town preview keeps management reachable without losing placement") {
        withUi(realHistory = true) { h ->
            h.city = settlement(h.player)
            val preview = preview().copy(level = 2, movable = false)
            h.preview = preview
            h.open()
            h.screen.id shouldBe "civs.preview"
            h.click("management")
            h.screen.id shouldBe "civs.management"
            h.click("history")
            h.screen.id shouldBe "civs.preview"
            h.preview shouldBe preview
            h.click("placement")
            h.ui.showingDialog(h.player) shouldBe false
            h.preview shouldBe preview
            verify(exactly = 0) { h.host.cancelPreview(any()); h.host.confirmPreview(any()); h.host.movePreview(any()) }
        }
    }

    test("the owner deletes the captured town and the completed flow cannot return to its overview") {
        withUi(realHistory = true) { h ->
            val base = settlement(h.player)
            val city = base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL), construction = CivConstruction(
                UUID.randomUUID(), base.buildings.last().id, 2, 100, 20, ConstructionPhase.CONSTRUCTING,
                Stock(), materials = mapOf("OAK_LOG" to 24L)))
            h.city = city
            every { h.host.deleteCity(any(), any(), any()) } answers {
                h.city = null
                CompletableFuture.completedFuture(Unit)
            }
            h.open()
            h.click("management")
            h.screen.buttons.single { it.id.value == "delete_city" }.label.color() shouldBe TextColor.color(0xFF6B61)
            h.click("delete_city")
            h.body() shouldContain "Buildings and items in chests and barrels remain"
            h.body() shouldContain "Starting materials are not granted again"
            h.body() shouldContain "Materials already spent are not refunded"
            h.click("delete", mapOf("delete_city_name" to city.name))
            h.paper.performTicks(2)
            verify(exactly = 1) { h.host.deleteCity(h.player, city.id, city.name) }
            h.screen.id shouldBe "civs.welcome"
            h.click("history")
            h.ui.showingDialog(h.player) shouldBe false
            h.screen.id shouldBe "civs.welcome"
        }
    }

    test("a wrong deletion name stays in the form without submitting the destructive action") {
        withUi { h ->
            h.city = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.VIRTUAL, mapOf("OAK_LOG" to 1L)))
            h.open(); h.click("management"); h.click("delete_city")
            h.click("delete", mapOf("delete_city_name" to "oak town"))
            h.screen.id shouldBe "civs.delete-city"
            h.screen.inputs.single().initial shouldBe "oak town"
            h.body() shouldContain "The name does not match"
            verify(exactly = 0) { h.host.deleteCity(any(), any(), any()) }
        }
    }

    test("busy deletion stays visible without a confirm button and backend errors retain the exact name") {
        withUi { h ->
            val city = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.VIRTUAL))
            h.city = city
            every { h.host.cityBusy(h.player) } returns true
            h.open(); h.click("management"); h.click("delete_city")
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            h.body() shouldContain "Another settlement action"
            every { h.host.cityBusy(h.player) } returns false
            h.open(); h.click("management"); h.click("delete_city")
            every { h.host.deleteCity(any(), any(), any()) } returns CompletableFuture.failedFuture(CivsRejection("city-busy"))
            h.click("delete", mapOf("delete_city_name" to city.name)); h.paper.performTicks(2)
            h.screen.inputs.single().initial shouldBe city.name
            h.body() shouldContain "Another settlement action"
            verify(exactly = 1) { h.host.deleteCity(h.player, city.id, city.name) }
        }
    }

    test("shared reserve building warehouses and legacy stock warn without blocking exact-name deletion") {
        withUi { h ->
            val base = settlement(h.player)
            every { h.host.deleteCity(any(), any(), any()) } answers {
                h.city = null
                CompletableFuture.completedFuture(Unit)
            }
            for (city in listOf(
                base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL, mapOf("OAK_LOG" to 1L))),
                base.copy(inventory = CivInventory(CivStorageMode.VIRTUAL,
                    buildingMaterials = mapOf(base.buildings.last().id.toString() to mapOf("STONE" to 1L)))),
                base.copy(inventory = null, stock = Stock(food = 1)),
            )) {
                h.city = city
                h.open(); h.click("management"); h.click("delete_city")
                h.body() shouldContain "Any virtual stock left in building warehouses or the shared reserve will be lost"
                h.body() shouldContain "Buildings and items in chests and barrels remain"
                h.screen.inputs.size shouldBe 1
                h.screen.buttons.map { it.id.value }.contains("delete") shouldBe true
                if (city.inventory != null) {
                    h.click("stock")
                    h.screen.id shouldBe "civs.stock"
                    h.open(); h.click("management"); h.click("delete_city")
                }
                h.click("delete", mapOf("delete_city_name" to city.name)); h.paper.performTicks(2)
                h.screen.id shouldBe "civs.welcome"
            }
            verify(exactly = 3) { h.host.deleteCity(h.player, base.id, base.name) }
        }
    }

    test("deletion warns before a later delivery and still submits the confirmed settlement") {
        withUi { h ->
            val city = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.VIRTUAL))
            h.city = city
            h.open(); h.click("management"); h.click("delete_city")
            h.body() shouldContain "Any virtual stock left in building warehouses or the shared reserve will be lost"
            h.city = city.copy(inventory = city.inventory!!.copy(materials = mapOf("BREAD" to 1L)))
            every { h.host.deleteCity(any(), any(), any()) } answers {
                h.city = null
                CompletableFuture.completedFuture(Unit)
            }
            h.click("delete", mapOf("delete_city_name" to city.name)); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.deleteCity(h.player, city.id, city.name) }
            h.screen.id shouldBe "civs.welcome"
            val message = requireNotNull(h.player.nextComponentMessage())
            message.color() shouldBe CivsText.SUCCESS
            plainText(message) shouldContain "deleted"
        }
    }

    test("a stale deletion form passes its original town id and cannot target a replacement with the same name") {
        withUi { h ->
            val original = settlement(h.player).copy(inventory = CivInventory(CivStorageMode.VIRTUAL))
            h.city = original
            h.open(); h.click("management"); h.click("delete_city")
            val replacement = original.copy(id = UUID.randomUUID())
            h.city = replacement
            every { h.host.deleteCity(any(), any(), any()) } returns CompletableFuture.failedFuture(CivsRejection("delete-city-changed"))
            h.click("delete", mapOf("delete_city_name" to original.name)); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.deleteCity(h.player, original.id, original.name) }
            verify(exactly = 0) { h.host.deleteCity(h.player, replacement.id, any()) }
            h.body() shouldContain "The town has changed or was already deleted"
            h.screen.buttons.map { it.id.value } shouldBe listOf("return")
            h.screen.inputs shouldBe emptyList()
            h.click("return")
            h.screen.id shouldBe "civs.overview"
            h.city shouldBe replacement
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
                "The action did not complete. Reopen this menu and try again."
        }
    }

    test("export representative building and warehouse screens from actual presenter components") {
        val directory = System.getenv("CIVS_UI_PREVIEW_OUT") ?: return@test
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.open(); h.click("found"); h.click("style_0")
            exportUiScreen(directory, "founding-barrel", h.screen)
            every { h.host.beginFoundingSelection(any(), any(), any()) } answers {
                h.selection = CivsSelectionView(UUID.randomUUID(), h.player.world.uid, BuildingType.TOWN_HALL,
                    System.currentTimeMillis() + 120_000, false)
                CompletableFuture.completedFuture(Unit)
            }
            h.click("prepare", mapOf("city_name" to "Oak Town")); h.paper.performTicks(2)
            exportUiActionBar(directory, "selection-barrel", requireNotNull(h.player.nextActionBar()))
            h.selection = null
            h.preview = preview()
            h.open()
            exportUiScreen(directory, "preview-barrel", h.screen)
            h.preview = null
            val base = settlement(h.player)
            val building = base.buildings.last()
            val own = listOf("BIRCH_LOG", "BRICKS", "CARROT", "COBBLESTONE", "DIRT", "GLASS", "OAK_LOG", "POTATO", "SAND", "STONE")
                .associateWith { 32L }
            h.city = base.copy(inventory = CivInventory(CivStorageMode.VIRTUAL, mapOf("BREAD" to 12L, "OAK_LOG" to 64L),
                buildingMaterials = mapOf(building.id.toString() to own)))
            h.ui.openBuilding(h.player, base.id, building.id)
            exportUiScreen(directory, "building", h.screen)
            h.click("warehouse")
            exportUiScreen(directory, "warehouse", h.screen)
            every { h.host.openBuildingInventory(h.player, building.id) } returns CompletableFuture.failedFuture(CivsRejection("control-missing"))
            h.click("intake"); h.paper.performTicks(2)
            exportUiScreen(directory, "control-missing", h.screen)
            every { h.host.openBuildingInventory(h.player, building.id) } returns CompletableFuture.completedFuture(Unit)
            h.ui.openBuilding(h.player, base.id, building.id); h.click("warehouse")
            h.click("next")
            exportUiScreen(directory, "warehouse-page-2", h.screen)
            h.click("withdraw")
            exportUiScreen(directory, "withdraw", h.screen)
            h.ui.openBuilding(h.player, base.id, building.id); h.click("production")
            exportUiScreen(directory, "production", h.screen)
            every { h.host.cityBusy(h.player) } returns true
            h.ui.openBuilding(h.player, base.id, building.id); h.click("warehouse")
            exportUiScreen(directory, "warehouse-busy", h.screen)
            every { h.host.cityBusy(h.player) } returns false
            h.city = h.city!!.copy(buildings = h.city!!.buildings.map { if (it.id == building.id) it.copy(phase = BuildingPhase.SITE) else it })
            every { h.host.buildingMaterials(h.player, building.id) } returns CompletableFuture.completedFuture(
                mapOf("OAK_LOG" to 124L, "COBBLESTONE" to 260L, "GLASS" to 16L))
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials"); h.paper.performTicks(2)
            exportUiScreen(directory, "site-materials", h.screen)
            h.open(); h.click("management"); h.click("delete_city")
            exportUiScreen(directory, "delete-stock", h.screen)
            h.city = base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL, mapOf("BREAD" to 12L),
                buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 64L))))
            h.open(); h.click("management"); h.click("stock")
            exportUiScreen(directory, "stock-physical", h.screen)
            h.city = base.copy(inventory = CivInventory(CivStorageMode.VIRTUAL), construction = CivConstruction(
                UUID.randomUUID(), building.id, 2, 100, 20, ConstructionPhase.CONSTRUCTING, Stock(), materials = mapOf("OAK_LOG" to 24L)))
            h.open()
            exportUiScreen(directory, "overview", h.screen)
            h.click("guide")
            exportUiScreen(directory, "guide", h.screen)
            h.open()
            h.click("management")
            exportUiScreen(directory, "management-owner", h.screen)
            h.click("delete_city")
            exportUiScreen(directory, "delete-ready", h.screen)
            h.click("delete", mapOf("delete_city_name" to "Oak town"))
            exportUiScreen(directory, "delete-name-error", h.screen)
            h.city = base.copy(owner = UUID.randomUUID(), members = setOf(h.player.uniqueId))
            h.open(); h.click("management")
            exportUiScreen(directory, "management-member", h.screen)
            h.preview = preview().copy(level = 2, movable = false)
            h.open()
            exportUiScreen(directory, "preview-management", h.screen)
            h.preview = null
            val legacy = building.copy(control = building.anchor)
            val target = CivPosition(22, 66, 2)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == legacy.id) legacy else it })
            every { h.host.buildingControl(h.player, legacy.id) } returns CompletableFuture.completedFuture(target)
            h.ui.openBuilding(h.player, base.id, legacy.id)
            exportUiScreen(directory, "control-legacy-building", h.screen)
            h.click("relocate_control"); h.paper.performTicks(2)
            exportUiScreen(directory, "control-relocate-ready", h.screen)
            every { h.host.relocateControl(h.player, legacy.id, target) } returns CompletableFuture.failedFuture(
                CivsRejection("control-position-missing", mapOf("x" to "22", "y" to "66", "z" to "2")))
            h.click("relocate"); h.paper.performTicks(2)
            exportUiScreen(directory, "control-relocate-missing", h.screen)
            val busyLookup = CompletableFuture<CivPosition>()
            every { h.host.buildingControl(h.player, legacy.id) } returns busyLookup
            h.ui.openBuilding(h.player, base.id, legacy.id); h.click("relocate_control")
            every { h.host.cityBusy(h.player) } returns true
            busyLookup.complete(target); h.paper.performTicks(2)
            exportUiScreen(directory, "control-relocate-busy", h.screen)
            every { h.host.cityBusy(h.player) } returns false
            val site = base.buildings.first().copy(phase = BuildingPhase.SITE, level = 1)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == site.id) site else it })
            every { h.host.buildingMaterials(h.player, site.id) } returns CompletableFuture.completedFuture(materialEstimate())
            every { h.host.availableMaterials(h.player) } returns mapOf("COBBLESTONE" to 5L, "SPRUCE_PLANKS" to 9_000_001L)
            h.ui.openBuilding(h.player, base.id, site.id); h.click("materials"); h.paper.performTicks(2)
            exportUiScreen(directory, "materials-site-ru", h.screen)
            h.click("next")
            exportUiScreen(directory, "materials-site-page-2-ru", h.screen)
            h.player.setLocale(Locale.US)
            h.ui.openBuilding(h.player, base.id, site.id); h.click("materials"); h.paper.performTicks(2)
            exportUiScreen(directory, "materials-site-en", h.screen)
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.city = base.copy(construction = CivConstruction(UUID.randomUUID(), site.id, 2, 100, 40,
                ConstructionPhase.WAITING_MATERIALS, Stock(), materials = mapOf("COBBLESTONE" to 19L, "SPRUCE_PLANKS" to 12L),
                payment = CivConstructionPayment(awaiting = mapOf("COBBLESTONE" to 3L))))
            h.ui.openBuilding(h.player, base.id, site.id); h.click("materials")
            exportUiScreen(directory, "materials-waiting-ru", h.screen)
            h.screens.any { screen -> screen.buttons.any { it.id.value == "refresh" } } shouldBe false
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
        every { host.cityBusy(any()) } returns false
        every { host.availableMaterials(any()) } answers {
            city?.inventory?.materials.orEmpty().toMutableMap().also { total ->
                city?.inventory?.buildingMaterials.orEmpty().values.forEach { stock ->
                    stock.forEach { (material, count) -> total[material] = (total[material] ?: 0) + count }
                }
            }
        }
        every { host.buildingMaterials(any(), any()) } returns CompletableFuture.completedFuture(emptyMap())
        every { host.buildingControl(any(), any()) } answers {
            val building = requireNotNull(city?.building(secondArg()))
            CompletableFuture.completedFuture(building.anchor.copy(y = building.anchor.y + 2))
        }
        every { host.relocateControl(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.startBuilding(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.deleteCity(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.openBuildingInventory(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.materializeStorage(any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.depositMaterials(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.withdrawMaterial(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
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
    CivPosition(0, 65, 0), CivBox(CivPosition(-6, 64, -12), CivPosition(5, 73, -1)), true, false,
    control = CivPosition(0, 66, -6))

private fun materialEstimate() = mapOf("COBBLESTONE" to 73L, "COBBLESTONE_STAIRS" to 22L,
    "COBBLESTONE_WALL" to 28L, "DIRT" to 516L, "OAK_PLANKS" to 48L, "RED_BED" to 1L,
    "SPRUCE_DOOR" to 2L, "SPRUCE_FENCE" to 90L, "SPRUCE_PLANKS" to 2_000_003L)

private fun settlement(player: Player): Settlement {
    fun building(type: BuildingType, x: Int, level: Int = 1) = CivBuilding(UUID.randomUUID(), type, level,
        CivPosition(x, 64, 0), 0, "medieval-oak/${type.name.lowercase()}",
        CivBox(CivPosition(x, 64, 0), CivPosition(x + 4, 70, 4)), CivPosition(x + 2, 64, -1), BuildingPhase.ACTIVE, control = CivPosition(x + 2, 65, 2))
    val hall = building(BuildingType.TOWN_HALL, 0, 2)
    val house = building(BuildingType.HOUSE, 10)
    val builder = building(BuildingType.BUILDER_HUT, -10)
    val forest = building(BuildingType.FORESTER, 20)
    return Settlement(UUID.randomUUID(), player.uniqueId, "Oak Town", player.world.uid, CivPosition(0, 64, 0), 64,
        "medieval-oak", Stock(), listOf(hall, house, builder, forest),
        listOf(CivWorker(UUID.randomUUID(), "Builder", builder.id, nextDeliveryAt = 10_000),
            CivWorker(UUID.randomUUID(), "Resident A", null), CivWorker(UUID.randomUUID(), "Resident B", null),
            CivWorker(UUID.randomUUID(), "Forester", null)), inventory = CivInventory.starting(Stock(96, 96, 24)))
}

/** Opt-in source export for the shared native dialog renderer; no UI behavior is mirrored here. */
private fun exportUiScreen(directory: String, id: String, screen: PaperDialogScreen) {
    val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    val content = linkedMapOf("title" to legacy.serialize(screen.title))
    val body = screen.body.mapIndexed { index, value ->
        val key = "body-$index"
        content[key] = legacy.serialize(value.text)
        mapOf("id" to key, "text-key" to key, "width" to value.width)
    }
    val inputs = screen.inputs.map { value ->
        val key = value.id.value
        content["$key-label"] = legacy.serialize(value.label)
        content["$key-initial"] = value.initial
        mapOf("id" to key, "label-key" to "$key-label", "initial-key" to "$key-initial", "width" to value.width)
    }
    fun button(value: ru.arc.paper.menu.PaperDialogButton): Map<String, Any> {
        val key = value.id.value
        content[key] = legacy.serialize(value.label)
        content["$key-tooltip"] = legacy.serialize(value.tooltip)
        return mapOf("id" to key, "text-key" to key, "tooltip-key" to "$key-tooltip", "width" to value.width)
    }
    val dialog = mapOf("id" to id, "source" to id, "format" to "reference-multi-action", "name-key" to "title",
        "external-title-key" to "title", "columns" to screen.columns, "body" to body,
        "buttons" to screen.buttons.map(::button), "exit-button" to screen.exitButton?.let(::button),
        "viewport" to listOf(854, 480), "variants" to listOf(mapOf("id" to "example"))) +
        if (inputs.isEmpty()) emptyMap() else mapOf("inputs" to inputs)
    val out = Path.of(directory)
    Files.createDirectories(out)
    Files.writeString(out.resolve("$id.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(
        mapOf("content" to content, "dialog" to dialog, "sourceSha256" to uiSourceHashes(),
            "evidence" to "Actual native dialog presenter components with fixture town state and server-resolved material names. No native-client acceptance.")))
}

private fun exportUiActionBar(directory: String, id: String, component: Component) {
    val out = Path.of(directory)
    Files.createDirectories(out)
    val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    Files.writeString(out.resolve("$id.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(mapOf(
        "content" to mapOf("text" to legacy.serialize(component)),
        "hud" to mapOf("id" to id, "actionbar" to "$id.text"),
        "sourceSha256" to uiSourceHashes(),
        "evidence" to "Actual actionbar captured after the founding form yields to ground or barrel selection. No native-client acceptance.",
    )))
}

private fun uiSourceHashes(): Map<String, String> = listOf(
    "src/main/kotlin/ru/arc/civs/CivsUi.kt", "src/main/kotlin/ru/arc/civs/CivsText.kt", "src/main/resources/locales.yml",
    "src/main/resources/materials/ru.json", "src/main/resources/materials/en.json",
).associateWith { source ->
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(source))).joinToString("") { "%02x".format(it) }
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
