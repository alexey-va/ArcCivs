package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContain
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
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.GameMode
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
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
    test("research follows the selected Auto through start pause and retained project progress") {
        withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val personal = settlement(h.player)
            var village = autonomousSettlement(h.player, "Selected research Auto")
            val projectId = UUID.randomUUID()
            h.city = personal
            every { h.player.hasPermission("arccivs.admin") } returns true
            every { h.host.settlements() } answers { listOf(personal, village) }
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("planning")
            every { h.host.research(h.player, village.id) } answers { CivsResearch.view(village) }
            every { h.host.beginResearch(h.player, village.id, CivResearchId.MATERIALS) } answers {
                village = village.copy(research = CivResearchState(project = CivResearchProject(projectId,
                    CivResearchId.MATERIALS, CivResearchCost(stock = Stock(24, 16, 4)), 3)))
                CompletableFuture.completedFuture(Unit)
            }
            every { h.host.setResearchPaused(h.player, village.id, projectId, any()) } answers {
                village = village.copy(research = village.research!!.copy(project = village.research!!.project!!.copy(paused = arg(3))))
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openAutonomous(h.player, village.id)
            h.click("research"); h.click("research_materials")
            h.body() shouldContain village.name
            h.body().contains(personal.name) shouldBe false
            h.click("start"); h.paper.performTicks(2)
            h.body() shouldContain "Ожидает материалы"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "research-auto-waiting-ru", h.screen, listOf(640, 360)) }
            village = village.copy(research = village.research!!.copy(project = village.research!!.project!!.copy(funded = true, workDone = 1)))
            h.click("history"); h.click("research_materials")
            h.body() shouldContain "1 / 3"
            h.body() shouldContain "Материалы уже выделены"
            h.click("pause"); h.paper.performTicks(2)
            h.body() shouldContain "Проект на паузе"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "research-auto-paused-ru", h.screen, listOf(640, 360)) }
            h.click("pause"); h.paper.performTicks(2)
            village.research!!.project!!.workDone shouldBe 1
            village.research!!.project!!.funded shouldBe true
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.click("pause"); h.paper.performTicks(2)
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            verify(exactly = 1) { h.host.beginResearch(h.player, village.id, CivResearchId.MATERIALS) }
            verify(exactly = 1) { h.host.setResearchPaused(h.player, village.id, projectId, true) }
            verify(exactly = 1) { h.host.setResearchPaused(h.player, village.id, projectId, false) }
            verify(exactly = 0) { h.host.research(h.player, personal.id); h.host.beginResearch(h.player, personal.id, any()) }
        }
    }

    test("Auto visitors can inspect research and closed products without management actions") {
        for (language in listOf("ru", "en")) for (font in listOf("", "arc:dialog_ui")) withUi(realHistory = true, decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val personal = settlement(h.player)
            val base = autonomousSettlement(h.player, "Research village")
            val university = base.buildings.first { it.type == BuildingType.UNIVERSITY }
            val village = base.copy(owner = UUID.randomUUID(), autonomy = base.autonomy!!.copy(products =
                mapOf(CivIndustryProduct.RAW_IRON to 12L, CivIndustryProduct.CHARCOAL to 2L)), research = CivResearchState(setOf(CivResearchId.MATERIALS),
                CivResearchProject(UUID.randomUUID(), CivResearchId.METALLURGY, CivsResearch.cost(base, CivResearchId.METALLURGY), 5, 2, true)))
            h.city = personal
            every { h.player.hasPermission("arccivs.admin") } returns false
            every { h.host.settlements() } returns listOf(personal, village)
            every { h.host.inspectBuilding(h.player, village.id, university.id) } returns village
            every { h.host.research(h.player, village.id) } returns CivsResearch.view(village)
            every { h.host.industry(h.player, village.id) } returns CivsIndustry.view(village)
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                exportUiScreen(it, "$id-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360))
            } }
            h.ui.openBuildingInspection(h.player, village.id, university.id); h.click("research")
            h.screen.id shouldBe "civs.research"
            h.body() shouldContain "2 / 5"
            export("research-overview")
            h.click("research_metallurgy")
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            h.body() shouldContain if (language == "ru") "Материалы уже выделены" else "Materials already paid"
            export("research-funded")
            h.click("history"); h.click("research_engineering")
            h.screen.buttons.map { it.id.value } shouldBe emptyList()
            h.body() shouldContain if (language == "ru") "университет уровня 2" else "level 2 university"
            export("research-locked")
            h.click("history"); h.click("industry")
            // Native wrapping and font spacers may split a sentence without changing its meaning.
            h.body().replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ") shouldContain
                if (language == "ru") "в предметы не превращаются" else "never become inventory items"
            h.body().contains(if (language == "ru") "Общий запас" else "Shared stock") shouldBe false
            export("industry-auto-recipes")
            verify(exactly = 0) { h.host.beginResearch(any(), any(), any()); h.host.setResearchPaused(any(), any(), any(), any()); h.host.setProduction(any(), any(), any()) }
        }
    }

    test("personal industry shows exact batch inputs and gates the new catalogue without hiding existing tools") {
        for (language in listOf("ru", "en")) withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val base = settlement(h.player)
            val smeltery = base.buildings.last().copy(type = BuildingType.SMELTERY, production = "CHARCOAL")
            h.city = base.copy(buildings = base.buildings + smeltery.copy(id = UUID.randomUUID()))
            val actual = h.city!!.buildings.last()
            every { h.host.industry(h.player, base.id) } answers { CivsIndustry.view(h.city!!) }
            every { h.host.setProduction(h.player, actual.id, "STONE") } answers {
                h.city = h.city!!.copy(buildings = h.city!!.buildings.map { if (it.id == actual.id) it.copy(production = "STONE") else it })
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openBuilding(h.player, base.id, actual.id); h.click("production")
            h.screen.id shouldBe "civs.industry-building"
            h.body() shouldContain "× 8"
            h.body() shouldContain "× 1"
            h.body() shouldContain "→"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "industry-native-$language", h.screen, listOf(640, 360)) }
            h.click("production_1"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.setProduction(h.player, actual.id, "STONE") }
            h.city = h.city!!.copy(research = CivResearchState(CivResearchId.entries.toSet()))
            h.ui.openBuilding(h.player, base.id, actual.id); h.click("production")
            h.screen.buttons.count { it.id.value.startsWith("production_") } shouldBe 4
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "industry-native-unlocked-$language", h.screen, listOf(640, 360)) }
            h.city = h.city!!.copy(research = null)
            h.open(); h.click("buildings"); h.click("catalog")
            h.click("offer_15")
            h.screen.buttons.map { it.id.value } shouldBe listOf("research")
            h.body() shouldContain if (language == "ru") "Материаловедение" else "Materials"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "industry-locked-offer-$language", h.screen, listOf(640, 360)) }
            h.click("history"); h.click("next")
            h.screen.buttons.filter { it.id.value.startsWith("offer_") }.size shouldBe 2
            h.click("offer_0")
            h.screen.buttons.map { it.id.value } shouldBe listOf("research")
        }
    }

    test("Auto development has no manual plan board placement or refresh action") {
        withUi(realHistory = true) { h ->
            val personal = settlement(h.player)
            val village = autonomousSettlement(h.player, "Selected Auto")
            h.city = personal
            every { h.player.hasPermission("arccivs.admin") } returns true
            every { h.host.settlements() } returns listOf(personal, village)
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("ready")
            h.ui.openAutonomous(h.player, village.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "plan_board"
            h.screen.buttons.map { it.id.value } shouldNotContain "refresh"
            verify(exactly = 0) { h.host.beginPlanBoardSelection(any(), any()); h.host.beginBuildingSelection(any(), any()) }
            h.city shouldBe personal
        }
    }

    test("city history follows the inspected autonomous visit while the administrator has a personal city") {
        withUi(realHistory = true) { h ->
            val personal = settlement(h.player)
            val village = autonomousSettlement(h.player, "History village")
            h.city = personal
            every { h.player.hasPermission("arccivs.admin") } returns true
            every { h.host.settlements() } returns listOf(personal, village)
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("ready")
            val folder = Files.createTempDirectory("civs-ui-history")
            val history = CivsCityHistory(folder.resolve("logs"), h.plugin.logger::warning)
            try {
                CivsDiagnostics.install(history, h.plugin.logger)
                h.ui.openAutonomous(h.player, village.id)
                h.click("workers")
                h.ui.close()
                history.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
                val text = Files.readString(folder.resolve("logs/settlements/${village.id}/latest.log"))
                text shouldContain "event=ui_screen"
                text shouldContain "event=ui_click"
                text shouldContain "screen=civs.autonomous-city"
                text shouldContain "screen=civs.autonomous-workers"
                Files.exists(folder.resolve("logs/settlements/${personal.id}")) shouldBe false
            } finally {
                h.ui.close()
                history.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS)
                CivsDiagnostics.detach(history)
                folder.toFile().deleteRecursively()
            }
        }
    }

    test("worker card keeps native vitals and actions while naming each fact") {
        for (locale in listOf("ru", "en")) for (font in listOf("", "arc:dialog_ui")) withUi(decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag(locale))
            val base = settlement(h.player)
            for ((sample, type, status) in listOf(Triple("worker-card", BuildingType.FARM, "moving-to-work"),
                Triple("worker-long", BuildingType.STONEMASON, "retreating"))) {
                val workplace = base.buildings.last().copy(type = type, level = 3)
                val worker = base.workers.first().copy(name = "Вячеслав", assignment = workplace.id,
                    health = 20.0, hunger = 0, skills = mapOf("FARM" to 40L), completedCycles = 453)
                h.city = base.copy(buildings = base.buildings.dropLast(1) + workplace, workers = listOf(worker))
                every { h.host.workerStatus(worker.id) } returns "workers.status.$status"
                h.ui.openWorker(h.player, base.id, worker.id)
                h.screen.buttons.map { it.id.value } shouldBe listOf("inventory", "skills", "assign", "unassign")
                val labels = if (locale == "ru") listOf("Профессия", "Место работы", "Состояние", "Настроение")
                    else listOf("Profession", "Workplace", "Status", "Happiness")
                val facts = plainText(h.screen.body[2].text).replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ")
                labels.forEach { facts shouldContain it }
                facts.contains(if (locale == "ru") "Завершено заданий" else "Completed jobs") shouldBe false
                facts.contains("453") shouldBe false
                plainText(h.screen.body[2].text).codePoints().anyMatch { it in 0x10A000..0x10A009 } shouldBe false
                val expectedVitals = CivsText.fromYaml(civsLocaleSource()).residentVitals(h.player, 20.0, 28.0, 0)
                h.screen.body[1].text shouldBe expectedVitals
                h.screen.body.count { plainText(it.text).codePoints().anyMatch { point -> point in 0x102000..0x103FFF } } shouldBe
                    if (font.isEmpty()) 0 else 1
                val suffix = if (font.isEmpty()) "plain-$locale" else locale
                System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "$sample-$suffix", h.screen, listOf(640, 360)) }
            }
        }
    }

    test("resident life profile explains actual needs aptitude home cap and child education without city controls") {
        for (language in listOf("ru", "en")) for (font in listOf("", "arc:dialog_ui")) withUi(realHistory = true, decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            every { h.player.hasPermission("arccivs.admin") } returns true
            val russian = language == "ru"
            val labels = CivsText.fromYaml(civsLocaleSource())
            val base = autonomousSettlement(h.player, "Ольховка")
            val home = base.buildings.first { it.type == BuildingType.HOUSE }.copy(level = 2)
            val farm = base.buildings.first { it.type == BuildingType.FARM }
            val tower = base.buildings.first { it.type == BuildingType.GUARD_TOWER }
            val resident = base.workers.first().copy(name = "Мирослава", assignment = farm.id, homeId = home.id,
                completedCycles = 453, hunger = 4, skills = mapOf("FARM" to 30L), pendingStock = Stock(13, 7, 4),
                life = CivResidentLife(awakeMinutes = 50, lastRestMinute = 1))
            val partner = base.workers[1].copy(homeId = home.id, assignment = null)
            val guard = base.workers[2].copy(assignment = tower.id)
            var village = base.copy(buildings = base.buildings.map { if (it.id == home.id) home else it },
                workers = listOf(resident, partner, guard), families = listOf(CivFamily(resident.id, partner.id, home.id, 0)))
            every { h.host.settlements() } answers { listOf(village) }
            every { h.host.inspectResident(h.player, village.id, resident.id) } answers { village }
            every { h.host.workerStatus(resident.id) } returns "workers.status.eating"
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "resident-life-$id-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) } }
            // Native paragraphs may wrap inside a label; compare its words without font-only positioning glyphs.
            fun profileText() = h.body().replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ")
            val aptitude = plainText(labels.render("profession.${CivsRules.profession(CivsResidentLife.aptitude(resident))}", language))
            h.ui.openWorker(h.player, village.id, resident.id)
            profileText() shouldContain aptitude
            profileText() shouldContain if (russian) "30 опыта" else "30 XP"
            profileText() shouldContain if (russian) "Грустный" else "Sad"
            profileText().contains("45 / 100") shouldBe false
            for (key in listOf("hungry", "housed", "rest_needed", "occupied", "family", "guarded"))
                profileText() shouldContain plainText(labels.render("workers.need.$key", language))
            if (font.isEmpty()) plainText(h.screen.body[3].text) shouldContain "\n\n"
            val needRows = if (font.isEmpty()) plainText(h.screen.body[3].text).lineSequence()
                .filter(String::isNotBlank).map { it.split(" · ") }.toList() else logisticsTableCells(h.screen)
            needRows.first() shouldBe listOf(if (russian) "Радует" else "Positive", if (russian) "Беспокоит" else "Concerns")
            needRows.drop(1).map { it[0] }.filter(String::isNotBlank) shouldBe listOf("housed", "occupied", "family", "guarded")
                .map { plainText(labels.render("workers.need.$it", language)) }
            needRows.drop(1).map { it[1] }.filter(String::isNotBlank) shouldBe listOf("hungry", "rest_needed")
                .map { plainText(labels.render("workers.need.$it", language)) }
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory", "skills")
            export("adult-dense")
            village = village.copy(workers = village.workers.map { if (it.id == resident.id) it.copy(hunger = 12) else it })
            h.ui.openWorker(h.player, village.id, resident.id)
            profileText() shouldContain if (russian) "Спокойный" else "Calm"
            export("adult-calm")
            h.click("skills")
            profileText() shouldContain if (russian) "30 опыта · предел дома" else "30 XP · home limit"
            export("adult-skills")

            val child = resident.copy(assignment = null, completedCycles = 0, nextDeliveryAt = 0, childMinutes = 18, hunger = 20, skills = null,
                parentIds = setOf(partner.id, guard.id), pendingStock = null,
                life = CivResidentLife(awakeMinutes = 50, lastRestMinute = 40, education = mapOf("FARM" to 3L, "FORESTER" to 2L)))
            village = village.copy(workers = listOf(child, partner, guard), families = emptyList())
            every { h.host.workerStatus(resident.id) } returns "workers.status.learning"
            h.ui.openWorker(h.player, village.id, resident.id)
            profileText() shouldContain aptitude
            profileText() shouldContain if (russian) "5 / 10 опыта" else "5 / 10 XP"
            profileText() shouldContain if (russian) "Довольный" else "Happy"
            profileText() shouldContain if (russian) "Учится у взрослого" else "Learning from an adult"
            for (key in listOf("fed", "rested")) profileText() shouldContain plainText(labels.render("workers.need.$key", language))
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory", "skills")
            export("child")
            h.click("skills")
            profileText() shouldContain if (russian) "3 опыта" else "3 XP"
            profileText() shouldContain if (russian) "2 опыта" else "2 XP"
            export("child-skills")

            val legacy = resident.copy(assignment = null, homeId = null, hunger = 12, life = null, pendingStock = null)
            village = village.copy(workers = listOf(legacy))
            every { h.host.workerStatus(resident.id) } returns "workers.status.to-meal"
            h.ui.openWorker(h.player, village.id, resident.id)
            profileText() shouldContain if (russian) "40 опыта" else "40 XP"
            for (key in listOf("needs_meal", "homeless", "jobless", "unguarded"))
                profileText() shouldContain plainText(labels.render("workers.need.$key", language))
            profileText().contains(plainText(labels.render("workers.need.rest_needed", language))) shouldBe false
            profileText().contains(plainText(labels.render("workers.need.rested", language))) shouldBe false
            profileText() shouldContain aptitude
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory", "skills")
            export("legacy")
            if (russian && font.isNotEmpty()) {
                val builder = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
                val roman = resident.copy(name = "Роман", sex = CivSex.MALE, assignment = builder.id,
                    hunger = 17, health = 20.0, completedCycles = 859, skills = mapOf("BUILDER_HUT" to 40L),
                    pendingStock = null, life = CivResidentLife(awakeMinutes = 50, lastRestMinute = 1))
                val workplaces = listOf(BuildingType.FORESTER, BuildingType.FARM, BuildingType.QUARRY, BuildingType.WAREHOUSE)
                    .map { type -> base.buildings.first { it.type == type }.id }
                val neighbours = workplaces.mapIndexed { index, workplace -> partner.copy(id = if (index == 0) partner.id else UUID.randomUUID(),
                    name = "Сосед ${index + 1}", assignment = workplace, homeId = if (index == 0) home.id else null) }
                village = base.copy(name = "Auto2", stock = Stock(216, 3, 288),
                    buildings = base.buildings.filter { it.type in CivsAutonomy.baseProfile }.map {
                        if (it.id == home.id) it.copy(level = 1) else it
                    } + home.copy(id = UUID.randomUUID(), level = 1), workers = listOf(roman) + neighbours,
                    families = listOf(CivFamily(roman.id, partner.id, home.id, 0)))
                every { h.host.workerStatus(roman.id) } returns "workers.status.placing"
                h.ui.openWorker(h.player, village.id, roman.id)
                profileText() shouldContain "Довольный"
                profileText() shouldContain "20 опыта"
                export("roman")
                val hall = village.buildings.first { it.type == BuildingType.TOWN_HALL }
                every { h.host.inspectBuilding(h.player, village.id, hall.id) } answers { village }
                every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("resources")
                every { h.host.nextAutonomyGoal(village.id) } returns CivAutonomyGoal(BuildingType.HOUSE, 1, null,
                    CivsAutonomy.cost(BuildingType.HOUSE, 1), Stock(0, 9))
                h.ui.openBuildingInspection(h.player, village.id, hall.id)
                h.body() shouldContain "Auto2"
                h.body() shouldContain "Нет активной стройки"
                h.body() shouldContain "Следующая цель"
                plainText(h.screen.buttons.single { it.id.value == "inventory" }.tooltip) shouldContain "можно брать и класть"
                export("auto2-hall")
            }
            val statuses = if (russian) mapOf("to-meal" to "Идёт поесть", "eating" to "Ест", "learning" to "Учится у взрослого",
                "patrolling" to "Обходит деревню", "alarm-shelter" to "Укрывается от опасности")
            else mapOf("to-meal" to "Going for a meal", "eating" to "Eating", "learning" to "Learning from an adult",
                "patrolling" to "Patrolling the village", "alarm-shelter" to "Taking shelter from danger")
            statuses.forEach { (status, expected) -> plainText(labels.status(h.player, status)) shouldBe expected }
        }
    }

    test("dialogue library keeps overview facts and actions with explicit font opt in and native fallback") {
        for (locale in listOf("ru", "en")) {
            for (font in listOf("", "arc:dialog_ui")) withUi(realHistory = true, decorationFont = font) { h ->
                h.player.setLocale(Locale.forLanguageTag(locale))
                h.city = settlement(h.player)
                h.open()
                h.screen.buttons.map { it.id.value } shouldBe listOf("management", "buildings", "workers", "research", "industry", "upgrade", "hall_inventory", "guide")
                h.body().codePoints().anyMatch { it in 0xF9000..0xF9FFF } shouldBe font.isNotEmpty()
                h.screen.body.count { plainText(it.text).codePoints().anyMatch { point -> point in 0xFA300..0xFAF1B } } shouldBe
                    if (font.isEmpty()) 0 else 1
                val suffix = if (font.isEmpty()) "plain-$locale" else locale
                fun export(name: String) = System.getenv("CIVS_UI_PREVIEW_OUT")?.let { directory ->
                    exportUiScreen(directory, "dialog-$name-$suffix", h.screen, listOf(640, 360))
                }
                export("overview")
                // Follow actual callbacks: each family keeps its native controls and typed action IDs.
                h.click("management"); export("management")
                h.click("stock"); export("stock")
                h.click("materials"); export("materials")
                h.open(); h.click("buildings"); export("buildings")
                h.click("building_0"); export("building")
                h.click("production"); export("production")
                h.open(); h.click("workers"); export("workers")
                h.click("worker_1"); export("worker")
                h.click("skills"); export("skills")
                h.click("next"); export("skills-last")
                var permitted = CivSettlementSetting.entries.toSet()
                every { h.host.canChangeSettlementSetting(h.player, any()) } answers { secondArg<CivSettlementSetting>() in permitted }
                h.open(); h.click("management"); h.click("testing_settings"); export("settings")
                h.screen.body.none { plainText(it.text).any { character -> character.code in 0xE540..0xE59F } } shouldBe true
                h.screen.buttons.map { it.id.value } shouldBe listOf("chunk_loading", "immortal_residents", "auto_construction_materials")
                permitted = permitted - CivSettlementSetting.IMMORTAL_RESIDENTS
                h.click("immortal_residents"); export("settings-denied")
                h.screen.buttons.map { it.id.value } shouldNotContain "immortal_residents"
                h.open(); h.click("management"); h.click("delete_city"); export("delete")
                h.city = h.city!!.copy(workers = emptyList(), inventory = h.city!!.inventory!!.copy(materials = emptyMap()))
                h.open(); h.click("workers"); export("workers-empty")
                h.screen.buttons.isEmpty() shouldBe true
                h.open(); h.click("management"); h.click("stock"); h.click("materials"); export("materials-empty")
                h.city = null
                h.open(); export("welcome")
                h.click("found"); export("styles")
                h.click("style_0"); export("name")
                h.city = settlement(h.player).copy(name = "Александровское поселение 123456")
                h.open()
                export("overview-long")
                h.click("guide")
                h.screen.id shouldBe "civs.guide"
                h.screen.buttons.isEmpty() shouldBe true
                h.body().codePoints().anyMatch { it in 0xF9000..0xF9FFF } shouldBe font.isNotEmpty()
                export("guide")
                val base = settlement(h.player)
                val active = base.buildings.last()
                val queued = active.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, phase = BuildingPhase.SITE)
                h.city = base.copy(buildings = base.buildings + queued,
                    construction = CivConstruction(UUID.randomUUID(), active.id, 1, 475, 40, ConstructionPhase.CONSTRUCTING,
                        Stock(), materials = mapOf("COBBLESTONE" to 19L), payment = CivConstructionPayment()),
                    constructionQueue = listOf(queued.id))
                every { h.host.buildingStatus(any()) } answers {
                    "building-status.${if (firstArg<UUID>() == active.id) "working" else "queued"}"
                }
                h.ui.openBuilding(h.player, base.id, queued.id)
                h.click("queue")
                h.screen.id shouldBe "civs.queue"
                h.body() shouldContain "40/475"
                h.screen.buttons.map { it.id.value } shouldBe listOf("active", "queue_0")
                h.body().codePoints().anyMatch { it in 0xF9000..0xF9FFF } shouldBe font.isNotEmpty()
                export("queue")
            }
        }
    }

    test("autonomous villages use their own admin context alongside a personal settlement") {
        withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            every { h.player.hasPermission("arccivs.admin") } returns true
            val personal = settlement(h.player).copy(name = "Личный город")
            h.city = personal
            var village = autonomousSettlement(h.player, "Ольховка")
            every { h.host.settlements() } answers { listOf(personal, village) }
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("resources", BuildingType.WAREHOUSE, 2,
                missing = Stock(12, 4))
            every { h.host.setAutonomousPaused(h.player, village.id, any()) } answers {
                village = village.copy(autonomy = village.autonomy!!.copy(paused = thirdArg()))
                CompletableFuture.completedFuture(Unit)
            }
            fun export(name: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "autonomy-$name-ru", h.screen) } }

            h.ui.openAutonomous(h.player)
            h.screen.id shouldBe "civs.autonomous"
            h.screen.buttons.map { it.id.value } shouldBe listOf("village_0", "create")
            h.screen.buttons.map { plainText(it.label) }.joinToString().contains(personal.name) shouldBe false
            export("list")
            h.click("village_0")
            h.screen.id shouldBe "civs.autonomous-city"
            h.body() shouldContain village.name
            h.body() shouldContain "Склад · ур. 2"
            h.body().contains("Древесина: 48") shouldBe false
            h.body().contains("Камень: 32") shouldBe false
            h.body().contains("Припасы: 16") shouldBe false
            h.body() shouldContain "Древесина: 12"
            h.body().contains(personal.name) shouldBe false
            h.screen.buttons.map { it.id.value } shouldBe listOf("buildings", "workers", "research", "industry", "pause", "villages")
            export("resources")
            h.click("buildings")
            h.screen.id shouldBe "civs.autonomous-buildings"
            materialTableCells(h.screen).drop(1).size shouldBe 8
            h.screen.buttons.map { it.id.value } shouldBe listOf("next")
            export("buildings")
            h.click("next")
            materialTableCells(h.screen).drop(1).size shouldBe village.buildings.size - 8
            h.screen.buttons.map { it.id.value } shouldBe listOf("previous")
            export("buildings-page-2")
            h.click("previous")
            h.click("history"); h.click("workers")
            h.screen.id shouldBe "civs.autonomous-workers"
            village.workers.forEach { h.body() shouldContain it.name }
            h.screen.buttons.map { it.id.value } shouldBe village.workers.indices.map { "worker_$it" }
            export("workers")
            h.click("history"); h.click("pause"); h.paper.performTicks(2)
            h.body() shouldContain "Развитие приостановлено"
            plainText(h.screen.buttons.single { it.id.value == "pause" }.label) shouldContain "Продолжить развитие"
            export("paused")
            h.click("pause"); h.paper.performTicks(2)
            h.body() shouldContain "Жители собирают ресурсы"
            h.city shouldBe personal
            verify(exactly = 1) { h.host.setAutonomousPaused(h.player, village.id, true) }
            verify(exactly = 1) { h.host.setAutonomousPaused(h.player, village.id, false) }
            verify(exactly = 0) { h.host.beginFoundingSelection(any(), any(), any()); h.host.assign(any(), any(), any()) }
        }
    }

    test("next expansion separates current construction from one truthful autonomous goal") {
        listOf("ru", "en").forEach { language ->
            listOf("", "arc:dialog_ui").forEach { font ->
                withUi(decorationFont = font) { h ->
                    val russian = language == "ru"
                    h.player.setLocale(Locale.forLanguageTag(language))
                    every { h.player.hasPermission("arccivs.admin") } returns true
                    val base = autonomousSettlement(h.player, "Ольховка").let { village ->
                        village.copy(buildings = village.buildings.filter { it.type != BuildingType.PLAN_BOARD }, stock = Stock(116, 60, 16))
                    }
                    val house = base.buildings.first { it.type == BuildingType.HOUSE }
                    val warehouse = base.buildings.first { it.type == BuildingType.WAREHOUSE }
                    var village = base
                    var goal: CivAutonomyGoal? = CivAutonomyGoal(BuildingType.PLAN_BOARD, 1, null, Stock(12, 8), Stock())
                    every { h.host.settlements() } answers { listOf(village) }
                    every { h.host.nextAutonomyGoal(village.id) } answers { goal }
                    every { h.host.autonomyStatus(village.id) } answers {
                        CivsAutonomyView(if (village.construction != null) "building" else if (goal == null) "complete"
                            else if (goal?.ready == false) "resources" else "planning")
                    }
                    every { h.host.buildingStatus(house.id) } returns "building-status.working"
                    fun show(name: String) {
                        h.ui.openAutonomous(h.player, village.id)
                        h.screen.id shouldBe "civs.autonomous-city"
                        System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                            exportUiScreen(it, "next-plan-$name${if (font.isEmpty()) "-plain" else ""}-$language", h.screen, listOf(640, 360))
                        }
                    }
                    show("ready")
                    h.body() shouldContain if (russian) "Следующая цель" else "Next goal"
                    h.body() shouldContain if (russian) "Построить: План поселения · ур. 1" else "Build: Settlement plan · level 1"
                    h.body() shouldContain if (russian) "Ресурсов достаточно" else "Resources available"
                    h.body().contains(if (russian) "Текущая стройка" else "Current construction") shouldBe false
                    plainText(h.screen.buttons.single { it.id.value == "industry" }.tooltip) shouldContain
                        if (russian) "в предметы инвентаря они не превращаются" else "they do not become inventory items"

                    village = base.copy(buildings = base.buildings.map { if (it.id == house.id) it.copy(phase = BuildingPhase.SITE) else it })
                    goal = CivAutonomyGoal(BuildingType.HOUSE, 1, house.id, CivsAutonomy.cost(BuildingType.HOUSE, 1), Stock())
                    show("site")
                    h.body() shouldContain if (russian) "Построить: Дом · ур. 1" else "Build: House · level 1"
                    h.body().contains(if (russian) "Улучшить:" else "Upgrade:") shouldBe false

                    village = base
                    goal = CivAutonomyGoal(BuildingType.WAREHOUSE, 2, warehouse.id, CivsAutonomy.cost(BuildingType.WAREHOUSE, 2), Stock(12, 4))
                    show("resources")
                    h.body() shouldContain if (russian) "Улучшить: Склад · ур. 2" else "Upgrade: Warehouse · level 2"
                    h.body() shouldContain if (russian) "Нужны ресурсы" else "Needs resources"
                    h.body() shouldContain if (russian) "Не хватает сейчас:" else "Currently missing:"
                    h.body() shouldContain if (russian) "Древесина: 12" else "Timber: 12"
                    h.body() shouldContain if (russian) "Камень: 4" else "Stone: 4"
                    h.body().contains(if (russian) "Ресурсов достаточно" else "Resources available") shouldBe false

                    village = base.copy(construction = CivConstruction(UUID.randomUUID(), house.id, 2, 100, 25,
                        ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = house.copy(level = 2)))
                    show("after-current")
                    h.body() shouldContain if (russian) "Текущая стройка" else "Current construction"
                    h.body() shouldContain "25/100"
                    h.body() shouldContain if (russian) "После этой стройки" else "After this construction"
                    h.body() shouldContain if (russian) "Улучшить: Склад · ур. 2" else "Upgrade: Warehouse · level 2"
                    goal = CivAutonomyGoal(BuildingType.TOWN_HALL, 2, base.buildings.first { it.type == BuildingType.TOWN_HALL }.id,
                        CivsAutonomy.cost(BuildingType.TOWN_HALL, 2), Stock(12, 36, 8))
                    show("after-current-all-resources")
                    h.body() shouldContain if (russian) "Припасы: 8" else "Supplies: 8"

                    village = base.copy(autonomy = CivAutonomy(paused = true))
                    goal = CivAutonomyGoal(BuildingType.PLAN_BOARD, 1, null, Stock(12, 8), Stock())
                    show("paused")
                    h.body() shouldContain if (russian) "Развитие приостановлено" else "Development paused"
                    h.body().contains(if (russian) "Ресурсов достаточно" else "Resources available") shouldBe false
                    plainText(h.screen.buttons.single { it.id.value == "pause" }.tooltip) shouldContain
                        if (russian) "Запасы и постройки сохраняются" else "Stock and buildings are preserved"

                    village = base.copy(construction = CivConstruction(UUID.randomUUID(), house.id, 2, 100, 25,
                        ConstructionPhase.CONSTRUCTING, Stock()))
                    goal = CivAutonomyGoal(BuildingType.HOUSE, 2, house.id, Stock(), Stock())
                    show("same-current")
                    h.body() shouldContain if (russian) "Новых целей пока нет." else "No further goal yet."
                    h.body().contains(if (russian) "Улучшить: Дом" else "Upgrade: House") shouldBe false

                    goal = null
                    village = autonomousSettlement(h.player, "Ольховка").let { complete ->
                        complete.copy(id = base.id, buildings = complete.buildings.map { it.copy(level = minOf(2, it.type.maxLevel)) })
                    }
                    show("complete")
                    h.body() shouldContain "${CivsAutonomy.profile.size} / ${CivsAutonomy.profile.size}"
                    h.body() shouldContain if (russian) "Новых целей пока нет." else "No further goal yet."
                }
            }
        }
    }

    test("next accepted queue entry wins over projections without duplicating current work") {
        listOf("ru", "en").forEach { language ->
            listOf("", "arc:dialog_ui").forEach { font ->
                withUi(decorationFont = font) { h ->
                    val russian = language == "ru"
                    h.player.setLocale(Locale.forLanguageTag(language))
                    val base = settlement(h.player)
                    val current = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
                    val house = base.buildings.first { it.type == BuildingType.HOUSE }
                    every { h.host.buildingStatus(current.id) } returns "building-status.working"
                    h.city = base.copy(construction = CivConstruction(UUID.randomUUID(), current.id, 2, 100, 25,
                        ConstructionPhase.CONSTRUCTING, Stock()), constructionQueue = listOf(current.id, house.id))
                    h.open()
                    h.body() shouldContain if (russian) "Следом в очереди" else "Next in the queue"
                    h.body() shouldContain if (russian) "Улучшить: Дом · ур. 2" else "Upgrade: House · level 2"
                    h.body().contains(if (russian) "Следующая цель" else "Next goal") shouldBe false
                    h.body().contains(if (russian) "Ратуша · ур. 3" else "Town hall · level 3") shouldBe false
                    h.screen.buttons.map { it.id.value } shouldContain "upgrade"
                    verify(exactly = 0) { h.host.nextAutonomyGoal(any()) }
                    // The stale current ID above exercises filtering; previews show the normal pending-only queue.
                    h.city = h.city!!.copy(constructionQueue = listOf(house.id))
                    h.open()
                    System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                        exportUiScreen(it, "next-plan-personal-queue${if (font.isEmpty()) "-plain" else ""}-$language", h.screen, listOf(640, 360))
                    }
                    every { h.player.hasPermission("arccivs.admin") } returns true
                    val village = h.city!!.copy(autonomy = CivAutonomy(), inventory = null,
                        buildings = h.city!!.buildings.map { if (it.id == house.id) it.copy(phase = BuildingPhase.SITE) else it })
                    every { h.host.settlements() } returns listOf(village)
                    every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("building")
                    every { h.host.nextAutonomyGoal(village.id) } returns
                        CivAutonomyGoal(BuildingType.PLAN_BOARD, 1, null, Stock(12, 8), Stock(12, 8))
                    h.ui.openAutonomous(h.player, village.id)
                    h.body() shouldContain if (russian) "Построить: Дом · ур. 1" else "Build: House · level 1"
                    h.body().contains(if (russian) "Построить: План поселения" else "Build: Settlement plan") shouldBe false
                    h.body().contains(if (russian) "Не хватает сейчас" else "Currently missing") shouldBe false
                    System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                        exportUiScreen(it, "next-plan-auto-queue${if (font.isEmpty()) "-plain" else ""}-$language", h.screen, listOf(640, 360))
                    }
                    h.city = base
                    h.open()
                    h.body().contains(if (russian) "Следом в очереди" else "Next in the queue") shouldBe false
                    h.body().contains(if (russian) "Следующая цель" else "Next goal") shouldBe false
                }
            }
        }
    }

    test("autonomous creation reuses ground selection while its preview omits personal material shopping") {
        withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            every { h.player.hasPermission("arccivs.admin") } returns true
            h.city = settlement(h.player)
            every { h.host.beginAutonomousSelection(h.player, "Новая деревня", "medieval-oak") } answers {
                h.selection = CivsSelectionView(UUID.randomUUID(), h.player.world.uid, BuildingType.TOWN_HALL,
                    System.currentTimeMillis() + 120_000, false)
                CompletableFuture.completedFuture(Unit)
            }
            fun export(name: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "autonomy-$name-ru", h.screen) } }
            h.ui.openAutonomous(h.player)
            h.body() shouldContain "Автономных деревень пока нет"
            h.screen.buttons.map { it.id.value } shouldBe listOf("create")
            export("empty")
            h.click("create"); h.screen.id shouldBe "civs.autonomous-styles"; export("styles")
            h.click("style_0"); h.screen.id shouldBe "civs.autonomous-name"; export("name")
            h.click("prepare", mapOf("city_name" to " "))
            h.screen.id shouldBe "civs.autonomous-name"
            h.body() shouldContain "Укажите непустое название"
            h.click("prepare", mapOf("city_name" to "Новая деревня")); h.paper.performTicks(2)
            h.ui.showingDialog(h.player) shouldBe false
            verify(exactly = 1) { h.host.beginAutonomousSelection(h.player, "Новая деревня", "medieval-oak") }
            verify(exactly = 0) { h.host.beginFoundingSelection(any(), any(), any()) }

            h.preview = preview().copy(autonomous = true, cityName = "Новая деревня", materials = materialEstimate())
            h.ui.openAutonomous(h.player)
            h.screen.id shouldBe "civs.preview"
            h.body() shouldContain "первая ратуша автономной деревни"
            h.body() shouldContain "общие ресурсы деревни"
            h.body().contains("Булыжник") shouldBe false
            h.body().contains("2 000 003") shouldBe false
            h.screen.buttons.map { it.id.value } shouldBe listOf("placement", "villages")
            export("preview")
            h.click("villages")
            h.screen.id shouldBe "civs.autonomous"
            h.screen.buttons.map { it.id.value } shouldBe listOf("create")
        }
    }

    test("autonomous creation and pause controls recheck revoked administrator permission") {
        withUi { h ->
            val village = autonomousSettlement(h.player, "Protected village")
            every { h.host.settlements() } returns listOf(village)
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("planning", BuildingType.HOUSE, 2)
            var admin = false
            every { h.player.hasPermission("arccivs.admin") } answers { admin }
            h.ui.openAutonomous(h.player, village.id)
            h.screen.id shouldBe "civs.autonomous-denied"
            h.body().contains(village.name) shouldBe false
            h.screen.buttons.isEmpty() shouldBe true
            admin = true
            h.ui.openAutonomous(h.player); h.click("create"); h.click("style_0")
            admin = false
            h.click("prepare", mapOf("city_name" to "No permission"))
            h.screen.id shouldBe "civs.autonomous-denied"
            admin = true
            h.ui.openAutonomous(h.player, village.id)
            val stale = h.screen
            admin = false
            h.click("pause")
            h.screen.id shouldBe "civs.autonomous-denied"
            h.click("pause", screen = stale)
            verify(exactly = 0) {
                h.host.beginAutonomousSelection(any(), any(), any())
                h.host.setAutonomousPaused(any(), any(), any())
            }
        }
    }

    test("autonomous construction keeps its guarded retry action separate from the village pause") {
        withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            var admin = true
            every { h.player.hasPermission("arccivs.admin") } answers { admin }
            val base = autonomousSettlement(h.player, "Стройка у реки")
            var village = base.copy(construction = CivConstruction(UUID.randomUUID(), base.buildings.first().id, 2, 475, 40,
                ConstructionPhase.PAUSED, Stock(), failure = "autonomy-no-path"))
            every { h.host.settlements() } answers { listOf(village) }
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("blocked", BuildingType.BUILDER_HUT, 2, "autonomy-no-path")
            every { h.host.retryConstruction(h.player, village.id) } returns CompletableFuture.completedFuture(Unit)
            h.ui.openAutonomous(h.player, village.id)
            h.screen.buttons.map { it.id.value } shouldBe listOf("buildings", "workers", "research", "industry", "retry", "pause", "villages")
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "autonomy-construction-paused-ru", h.screen) }
            h.click("retry"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.retryConstruction(h.player, village.id) }
            h.screen.id shouldBe "civs.autonomous-city"
            village = village.copy(autonomy = CivAutonomy(paused = true))
            h.ui.openAutonomous(h.player, village.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "retry"
            village = village.copy(autonomy = CivAutonomy())
            h.ui.openAutonomous(h.player, village.id)
            admin = false
            h.click("retry")
            h.screen.id shouldBe "civs.autonomous-denied"
            verify(exactly = 1) { h.host.retryConstruction(h.player, village.id) }
        }
    }

    test("Auto NPC keeps one resident profile and a read-only native bag in its explicit village") {
        for (language in listOf("ru", "en")) for (font in listOf("", "arc:dialog_ui")) withUi(realHistory = true, decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val personal = settlement(h.player).copy(name = "Personal town")
            val base = autonomousSettlement(h.player, "Auto village")
            val home = base.buildings.first { it.type == BuildingType.HOUSE }
            val workplace = base.buildings.first { it.type == BuildingType.FARM }
            val worker = base.workers.first().copy(name = "Мирослава", assignment = workplace.id, homeId = home.id,
                health = 17.0, hunger = 12, skills = mapOf("FARM" to 40L), pendingStock = Stock(13),
                items = List(33) { if (it == 6) CivResidentItem("BREAD", 3) else null })
            val village = base.copy(owner = UUID.randomUUID(), workers = listOf(worker), stock = Stock(83_411, 91_722, 72_433))
            h.city = personal
            every { h.player.hasPermission("arccivs.admin") } returns true
            every { h.host.settlements() } returns listOf(personal, village)
            every { h.host.inspectResident(h.player, village.id, worker.id) } returns village
            every { h.host.residentItems(h.player, village.id, worker.id) } returns CivsEquipment.slots(worker).map { it?.stack() }
            h.ui.openWorker(h.player, village.id, worker.id)
            h.screen.id shouldBe "civs.worker"
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory", "skills")
            h.body() shouldContain worker.name
            h.body().contains(personal.name) shouldBe false
            h.body().contains(village.name) shouldBe false
            h.body().contains("83411") shouldBe false
            h.body() shouldContain if (language == "ru") "Дом" else "Home"
            plainText(h.screen.exitButton!!.label) shouldBe if (language == "ru") "Закрыть" else "Close"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "resident-auto-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) }
            h.click("skills")
            h.screen.id shouldBe "civs.worker-skills"
            h.body() shouldContain worker.name
            h.click("history")
            h.screen.id shouldBe "civs.worker"
            h.click("inventory")
            val inventory = h.player.openInventory.topInventory
            inventory.getItem(18)!!.type shouldBe Material.BREAD
            inventory.getItem(18)!!.amount shouldBe 3
            inventory.getItem(15)!!.type shouldBe Material.BARREL
            plainText(inventory.getItem(49)!!.itemMeta.lore()!!.last()) shouldContain
                if (language == "ru") "Только просмотр" else "Read-only"
            h.nativeClick(18, ClickType.SHIFT_LEFT)
            h.player.inventory.setItem(9, ItemStack(Material.STONE, 2))
            h.nativeClick(54, ClickType.SHIFT_LEFT)
            inventory.getItem(18)!!.amount shouldBe 3
            h.nativeClick(45); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.worker"
            h.city shouldBe personal
            verify(exactly = 0) {
                h.host.residentItems(h.player, worker.id)
                h.host.transferResidentItem(any(), any(), any(), any(), any())
                h.host.giveWorkerRequest(any(), any(), any())
                h.host.assign(any(), any(), any())
                h.host.feedWorker(any(), any())
            }
            every { h.host.inspectResident(h.player, village.id, worker.id) } throws CivsRejection("not-authorized")
            h.click("skills")
            h.screen.buttons shouldBe emptyList()
        }
    }

    test("house inspection lists saved residents and beds while shared village stock belongs only to hall and warehouse") {
        for (language in listOf("ru", "en")) for (font in listOf("", "arc:dialog_ui")) withUi(realHistory = true, decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val base = autonomousSettlement(h.player, "Ольховка")
            val house = base.buildings.first { it.type == BuildingType.HOUSE }
            val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
            val warehouse = base.buildings.first { it.type == BuildingType.WAREHOUSE }
            var village = base.copy(owner = UUID.randomUUID(), stock = Stock(83_411, 91_722, 72_433),
                workers = listOf(base.workers.first().copy(name = "Мирослава", homeId = house.id, assignment = warehouse.id),
                    base.workers.last().copy(name = "Лада", homeId = house.id, assignment = null, childMinutes = 12)),
                construction = CivConstruction(UUID.randomUUID(), warehouse.id, 2, 100, 25, ConstructionPhase.CONSTRUCTING, Stock()))
            every { h.host.settlements() } answers { listOf(village) }
            every { h.host.inspectBuilding(h.player, village.id, any()) } answers { village }
            every { h.host.autonomyStatus(village.id) } returns CivsAutonomyView("building")
            every { h.host.nextAutonomyGoal(village.id) } returns CivAutonomyGoal(BuildingType.HOUSE, 2, house.id, Stock(50, 20), Stock(7, 3))
            h.ui.openBuildingInspection(h.player, village.id, house.id)
            h.body() shouldContain "Мирослава"
            h.body() shouldContain "Лада"
            h.body() shouldContain if (language == "ru") "Спальные места" else "Beds"
            h.body().contains(if (language == "ru") "Рабочие места" else "Workplaces") shouldBe false
            h.body().contains("83411") shouldBe false
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory")
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "auto-house-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) }
            for (building in listOf(hall, warehouse)) {
                h.ui.openBuildingInspection(h.player, village.id, building.id)
                h.body().contains("83411") shouldBe true
                h.body().contains("91722") shouldBe true
                h.body().contains("72433") shouldBe true
                if (building.type == BuildingType.TOWN_HALL) {
                    h.body() shouldContain "25"
                    h.body() shouldContain if (language == "ru") "После этой стройки" else "After this construction"
                    h.body() shouldContain if (language == "ru") "Не хватает сейчас" else "Currently missing"
                }
                System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                    "auto-${building.type.name.lowercase()}-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) }
            }
            every { h.player.hasPermission("arccivs.admin") } returns true
            village = village.copy(autonomy = village.autonomy!!.copy(products = CivIndustryProduct.entries.associateWith { 64L }))
            h.ui.openBuildingInspection(h.player, village.id, hall.id)
            h.screen.buttons.map { it.id.value } shouldBe listOf("workers", "buildings", "inventory")
            h.body() shouldContain if (language == "ru") "Набор инструментов" else "Tool kit"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "auto-town_hall-full-stock-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) }
            h.screens.forEach { screen -> screen.buttons.map { it.id.value } shouldNotContain "refresh" }
        }
    }

    test("ordinary Auto building inspection identifies its work and opens only the real city-specific barrel") {
        for (language in listOf("ru", "en")) withUi { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val personal = settlement(h.player)
            val base = autonomousSettlement(h.player, "Auto")
            val building = base.buildings.first { it.type == BuildingType.FORESTER }
            val village = base.copy(owner = UUID.randomUUID(), workers = listOf(
                base.workers.first().copy(name = "Мирослава", assignment = building.id)))
            h.city = personal
            every { h.host.settlements() } returns listOf(personal, village)
            every { h.host.inspectBuilding(h.player, village.id, building.id) } returns village
            every { h.host.openBuildingInventory(h.player, village.id, building.id) } returns CompletableFuture.completedFuture(Unit)
            h.ui.openBuildingInspection(h.player, village.id, building.id)
            h.screen.id shouldBe "civs.building-inspection"
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory")
            h.body() shouldContain "Auto"
            h.body() shouldContain "Мирослава"
            h.body().contains(if (language == "ru") "Общий запас деревни" else "Shared village stock") shouldBe false
            h.body() shouldContain if (language == "ru") "только для просмотра" else "read-only"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "auto-building-readonly-$language", h.screen, listOf(640, 360)) }
            h.click("inventory")
            verify(exactly = 1) { h.host.openBuildingInventory(h.player, village.id, building.id) }
            verify(exactly = 0) { h.host.openBuildingInventory(any(), any()); h.host.setProduction(any(), any(), any()) }
            h.city shouldBe personal
        }
    }

    test("physical Auto inspection shows only the selected building items instead of legacy resource counters") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru"))
            val base = autonomousSettlement(h.player, "Auto")
            val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
            val village = base.copy(owner = UUID.randomUUID(), stock = Stock(999, 998, 997),
                inventory = CivInventory(CivStorageMode.PHYSICAL))
            every { h.host.inspectBuilding(h.player, village.id, hall.id) } returns village
            every { h.host.inspectionMaterials(h.player, village.id, hall.id) } returns mapOf("OAK_LOG" to 64L)
            h.ui.openBuildingInspection(h.player, village.id, hall.id)
            h.body() shouldContain "Припасы в этом здании"
            h.body() shouldContain "64"
            listOf("999", "998", "997", "Общий запас деревни").forEach { h.body().contains(it) shouldBe false }
            verify(exactly = 1) { h.host.inspectionMaterials(h.player, village.id, hall.id) }
            verify(exactly = 0) { h.host.availableMaterials(any()) }
        }
    }

    test("Auto admin barrel details retain native failure and recheck inspection when reopened") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru"))
            h.player.addAttachment(h.plugin, "arccivs.admin", true)
            val village = autonomousSettlement(h.player, "Auto")
            val building = village.buildings.first { it.type == BuildingType.QUARRY }
            every { h.host.inspectBuilding(h.player, village.id, building.id) } returns village
            every { h.host.openBuildingInventory(h.player, village.id, building.id) } returns
                CompletableFuture.failedFuture(CivsRejection("storage-busy"))
            h.ui.openBuildingInspection(h.player, village.id, building.id)
            h.body() shouldContain "Админ-доступ"
            h.body() shouldContain "можно брать и класть"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "auto-building-admin-ru", h.screen, listOf(640, 360)) }
            h.click("inventory")
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.building-inspection"
            h.body() shouldContain plainText(CivsText.fromYaml(civsLocaleSource()).reason(h.player, "storage-busy"))
            every { h.host.inspectBuilding(h.player, village.id, building.id) } throws CivsRejection("worker-too-far")
            h.ui.openBuildingInspection(h.player, village.id, building.id)
            h.screen.buttons shouldBe emptyList()
            h.body() shouldContain plainText(CivsText.fromYaml(civsLocaleSource()).reason(h.player, "worker-too-far"))
        }
    }

    test("autonomous planner states and paginated rosters render in both locales and native fallback") {
        listOf("ru", "en").forEach { language ->
            listOf("", "arc:dialog_ui").forEach { font ->
                withUi(realHistory = true, decorationFont = font) { h ->
                    h.player.setLocale(Locale.forLanguageTag(language))
                    every { h.player.hasPermission("arccivs.admin") } returns true
                    h.city = settlement(h.player).copy(name = "Personal")
                    val base = autonomousSettlement(h.player, "Александровская деревня 123456")
                    var village = base
                    var status = CivsAutonomyView("searching", BuildingType.FORESTER, 2)
                    every { h.host.settlements() } answers { listOf(h.city!!, village) }
                    every { h.host.autonomyStatus(any()) } answers { status }
                    fun export(name: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                        exportUiScreen(it, "autonomy-$name${if (font.isEmpty()) "-plain" else ""}-$language", h.screen)
                    } }
                    for (stage in listOf("planning", "searching", "queued", "building", "resources", "blocked", "unhealthy", "paused", "complete", "future-stage")) {
                        village = when (stage) {
                            "complete" -> base.copy(buildings = base.buildings.map { it.copy(level = 2) })
                            "paused" -> base.copy(autonomy = CivAutonomy(paused = true))
                            else -> base
                        }
                        status = if (stage == "complete" || stage == "future-stage") CivsAutonomyView(stage) else
                            CivsAutonomyView(stage, BuildingType.FORESTER, 2,
                                reason = "autonomy-no-path".takeIf { stage == "blocked" }, missing = if (stage == "resources") Stock(64, 32, 8) else Stock())
                        h.ui.openAutonomous(h.player, village.id)
                        h.screen.id shouldBe "civs.autonomous-city"
                        h.body().contains("Personal") shouldBe false
                        h.body().contains("future-stage") shouldBe false
                        h.screen.buttons.map { it.id.value } shouldBe listOf("buildings", "workers", "research", "industry", "pause", "villages")
                        export(stage)
                    }
                    village = base.copy(workers = emptyList(), buildings = emptyList())
                    h.ui.openAutonomous(h.player, village.id); h.click("workers"); export("workers-empty")
                    h.screen.buttons.isEmpty() shouldBe true
                    h.click("history"); h.click("buildings"); export("buildings-empty")
                    h.screen.buttons.isEmpty() shouldBe true
                    village = base.copy(workers = List(10) { index -> base.workers.first().copy(id = UUID.randomUUID(), name = "Resident $index") })
                    h.ui.openAutonomous(h.player, village.id); h.click("workers"); h.click("next"); export("workers-page-2")
                    h.screen.buttons.map { it.id.value } shouldBe listOf("worker_0", "worker_1", "previous")
                    every { h.host.settlements() } returns listOf(h.city!!) + List(10) { index -> base.copy(id = UUID.randomUUID(), name = "Village $index") }
                    h.ui.openAutonomous(h.player); h.click("next"); export("list-page-2")
                    h.screen.buttons.map { it.id.value } shouldBe listOf("village_0", "village_1", "create", "previous")
                    every { h.host.settlements() } returns listOf(h.city!!)
                    h.ui.openAutonomous(h.player, base.id); export("removed")
                    h.body().contains(base.name) shouldBe false
                    h.screen.buttons.map { it.id.value } shouldBe listOf("return")
                }
            }
        }
    }

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
            h.body().contains("Дубовое бревно") shouldBe false
            h.body().contains("общий резерв") shouldBe false
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

    test("assignment transitions show the fresh workplace and source-owned worker status") {
        withUi { h ->
            h.city = settlement(h.player)
            val worker = h.city!!.workers.last()
            val forester = h.city!!.buildings.last()
            var assignedStatus = "moving-to-work"
            every { h.host.workerStatus(worker.id) } answers {
                "workers.status.${if (h.city!!.workers.single { it.id == worker.id }.assignment == null) "unassigned" else assignedStatus}"
            }
            every { h.host.assign(any(), any(), any()) } answers {
                h.city = CivsRules.assign(h.city!!, h.player.uniqueId, secondArg(), thirdArg(), 0)
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openWorker(h.player, h.city!!.id, worker.id)
            h.click("assign")
            val choices = h.screen
            val choice = choices.buttons.single { plainText(it.label).contains("Forester") }.id.value
            h.click(choice)
            h.click(choice, screen = choices)
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.worker"
            h.body() shouldContain "Forester"
            h.body() shouldContain "Walking to work"
            h.body().contains("No assignment") shouldBe false
            verify(exactly = 1) { h.host.assign(h.player, worker.id, forester.id) }

            assignedStatus = "working"
            h.click("unassign")
            h.paper.performTicks(2)
            h.body() shouldContain "No assignment"
            h.body().contains("Working") shouldBe false
            verify(exactly = 1) { h.host.assign(h.player, worker.id, null) }
            verify(exactly = 0) { h.runtime.close(h.player) }
        }
    }

    test("resident tool request stays visible and exposes an eligible one-shot handoff") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val worker = base.workers.first().copy(name = "Фёдор")
            h.city = base.copy(buildings = base.buildings.map { building ->
                if (building.id == worker.assignment) building.copy(type = BuildingType.FARM, schematicId = "medieval-oak/farm") else building
            }, workers = base.workers.map { if (it.id == worker.id) worker else it })
            var request: CivsBuildingRequest? = CivsBuildingRequest(Material.IRON_HOE, 1, false)
            every { h.host.workerRequest(h.player, worker.id) } answers { request }
            every { h.host.workerStatus(worker.id) } returns "workers.status.waiting-tool"
            h.ui.openWorker(h.player, base.id, worker.id)
            h.body() shouldContain "Для работы нужна мотыга"
            val missing = h.screen.buttons.single { it.id.value == "give_request" }
            plainText(missing.label) shouldBe "Выдать мотыгу"
            missing.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(missing.tooltip) shouldContain "Передать пока нельзя"
            h.click("give_request")
            verify(exactly = 0) { h.host.giveWorkerRequest(any(), any(), any()) }
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-tool-unavailable-ru", h.screen, listOf(588, 380)) }
            request = request!!.copy(canGive = true)
            val handoff = CompletableFuture<Unit>()
            every { h.host.giveWorkerRequest(h.player, worker.id, "IRON_HOE") } returns handoff
            h.ui.openWorker(h.player, base.id, worker.id)
            plainText(h.screen.buttons.first().label) shouldBe "Выдать мотыгу"
            h.screen.buttons.first().label.color() shouldBe TextColor.color(0x9BD48D)
            plainText(h.screen.buttons.first().tooltip) shouldContain "Передать один подходящий инструмент из своего инвентаря"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-tool-ready-ru", h.screen, listOf(588, 380)) }
            val ready = h.screen
            h.click("give_request"); h.click("give_request", screen = ready)
            verify(exactly = 1) { h.host.giveWorkerRequest(h.player, worker.id, "IRON_HOE") }
            request = null
            handoff.complete(Unit); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.worker"
            h.screen.buttons.map { it.id.value } shouldNotContain "give_request"
            h.body().contains("Для работы нужна мотыга") shouldBe false
        }
    }

    test("creative empty-inventory pickaxe request uses the host grant through resident and building dialog actions") {
        for (buildingScreen in listOf(false, true)) withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.player.gameMode = GameMode.SURVIVAL
            h.player.inventory.clear()
            val base = settlement(h.player)
            val worker = base.workers.first().copy(name = "Фёдор")
            val building = base.building(requireNotNull(worker.assignment))!!.copy(
                type = BuildingType.QUARRY, schematicId = "medieval-oak/quarry")
            h.city = base.copy(buildings = base.buildings.map { if (it.id == building.id) building else it },
                workers = base.workers.map { if (it.id == worker.id) worker else it })
            var request: CivsBuildingRequest? = CivsBuildingRequest(Material.IRON_PICKAXE, 1, false)
            every { h.host.workerRequest(h.player, worker.id) } answers { request }
            every { h.host.buildingRequest(h.player, building.id) } answers { request }
            every { h.host.workerStatus(worker.id) } returns "workers.status.waiting-tool"
            val receipt = CompletableFuture<Unit>()
            every { h.host.giveWorkerRequest(h.player, worker.id, "IRON_PICKAXE") } returns receipt
            every { h.host.giveBuildingRequest(h.player, building.id, "IRON_PICKAXE") } returns receipt
            fun open() {
                if (buildingScreen) h.ui.openBuilding(h.player, base.id, building.id)
                else h.ui.openWorker(h.player, base.id, worker.id)
            }
            open()
            var button = h.screen.buttons.single { it.id.value == "give_request" }
            button.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(button.tooltip) shouldContain "проверьте инструмент в своём инвентаре"
            h.click("give_request")
            h.player.gameMode = GameMode.CREATIVE
            open()
            button = h.screen.buttons.single { it.id.value == "give_request" }
            button.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(button.tooltip) shouldBe "Освободите место у жителя, чтобы выдать инструмент."
            h.click("give_request")
            verify(exactly = 0) { h.host.giveWorkerRequest(any(), any(), any()); h.host.giveBuildingRequest(any(), any(), any()) }
            request = request!!.copy(canGive = true)
            open()
            button = h.screen.buttons.single { it.id.value == "give_request" }
            plainText(button.label) shouldBe "Выдать кирку"
            button.label.color() shouldBe TextColor.color(0x9BD48D)
            plainText(button.tooltip) shouldBe "Создать нужный инструмент и выдать жителю в руку. Ваши предметы не расходуются."
            h.player.inventory.contents.all { it == null || it.type.isAir } shouldBe true
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "${if (buildingScreen) "quarry" else "resident"}-pickaxe-creative-ru", h.screen, listOf(588, 380)) }
            val ready = h.screen
            val renderCount = h.screens.size
            h.click("give_request", screen = ready)
            h.click("give_request", screen = ready)
            h.screens.size shouldBe renderCount
            h.screen shouldBe ready
            verify(exactly = if (buildingScreen) 1 else 0) { h.host.giveBuildingRequest(h.player, building.id, "IRON_PICKAXE") }
            verify(exactly = if (buildingScreen) 0 else 1) { h.host.giveWorkerRequest(h.player, worker.id, "IRON_PICKAXE") }
            verify(exactly = 0) { h.host.transferResidentItem(any(), any(), any(), any(), any()) }
            request = null
            receipt.complete(Unit)
            h.paper.performTicks(2)
            h.screens.size shouldBe renderCount + 1
            h.screen.buttons.map { it.id.value } shouldNotContain "give_request"
            h.player.inventory.contents.all { it == null || it.type.isAir } shouldBe true
        }
    }

    test("resident supplies show the batch remainder and hand it directly to the clicked resident") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val worker = base.workers.first().copy(name = "Фёдор")
            h.city = base.copy(buildings = base.buildings.map {
                if (it.id == worker.assignment) it.copy(type = BuildingType.QUARRY) else it
            }, workers = base.workers.map { if (it.id == worker.id) worker else it })
            var request: CivsBuildingRequest? = CivsBuildingRequest(Material.COBBLESTONE, 64, false)
            every { h.host.workerRequest(h.player, worker.id) } answers { request }
            every { h.host.workerStatus(worker.id) } returns "workers.status.waiting-cobblestone"
            h.ui.openWorker(h.player, base.id, worker.id)
            h.body() shouldContain "Нужно: Булыжник × 64"
            val missing = h.screen.buttons.single { it.id.value == "give_request" }
            plainText(missing.label) shouldBe "Выдать: Булыжник"
            missing.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(missing.tooltip) shouldBe "Нет предмета в инвентаре или места у жителя."
            val actions = h.screen.buttons.map { it.id }
            val rows = h.screen.body.size
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-supply-missing-ru", h.screen, listOf(588, 380)) }
            h.click("give_request")
            verify(exactly = 0) { h.host.giveWorkerRequest(any(), any(), any()); h.host.giveBuildingRequest(any(), any(), any()) }
            h.click("skills")
            h.screen.id shouldBe "civs.worker-skills"

            request = request!!.copy(canGive = true)
            val handoff = CompletableFuture<Unit>()
            every { h.host.giveWorkerRequest(h.player, worker.id, "COBBLESTONE") } returns handoff
            h.ui.openWorker(h.player, base.id, worker.id)
            h.screen.buttons.single { it.id.value == "give_request" }.label.color() shouldBe TextColor.color(0x9BD48D)
            h.screen.buttons.map { it.id } shouldBe actions
            h.screen.body.size shouldBe rows
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-supply-ready-ru", h.screen, listOf(588, 380)) }
            val ready = h.screen
            h.click("give_request"); h.click("give_request", screen = ready)
            verify(exactly = 1) { h.host.giveWorkerRequest(h.player, worker.id, "COBBLESTONE") }
            request = request!!.copy(amount = 48, canGive = false)
            handoff.complete(Unit); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.worker"
            h.body() shouldContain "Нужно: Булыжник × 48"
            h.screen.buttons.single { it.id.value == "give_request" }.label.color() shouldBe TextColor.color(0xFF6B61)
            request = null
            h.ui.openWorker(h.player, base.id, worker.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "give_request"
            verify(exactly = 0) { h.host.giveBuildingRequest(any(), any(), any()) }
        }
    }

    test("resident dialog refreshes vital glyphs with current health maximum and hunger") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val original = base.workers.first()
            val trained = original.copy(skills = mapOf("FARM" to 40L))
            val cases = listOf(
                Triple("full", original, "\uE600 20/20   \uE603 20/20"),
                Triple("partial", trained.copy(health = 4.0, hunger = 7), "\uE601 4/28   \uE604 7/20"),
                Triple("trained-full", trained.copy(health = 28.0, hunger = 20), "\uE600 28/28   \uE603 20/20"),
                Triple("empty-food", original.copy(health = 4.0, hunger = 0), "\uE601 4/20   \uE605 0/20"),
            )
            for ((id, worker, expected) in cases) {
                h.city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it })
                h.ui.openWorker(h.player, base.id, worker.id)
                h.body() shouldContain expected
                System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                    exportUiScreen(it, "resident-vitals-$id-ru", h.screen, listOf(588, 380))
                }
            }
        }
    }

    test("resident health skills and carried items use fresh state and real dialog history") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val worker = base.workers.first().copy(health = 11.5, skills = mapOf("BUILDER_HUT" to 13L, "FARM" to 40L),
                inventory = mapOf("BREAD" to 2L, "IRON_SHOVEL" to 1L, "IRON_CHESTPLATE" to 1L))
            h.city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it })
            h.ui.openWorker(h.player, base.id, worker.id)
            h.body() shouldContain "\uE601 11.5/28"
            h.body() shouldContain "\uE603 20/20"
            h.screen.buttons.map { it.id.value } shouldBe listOf("inventory", "skills", "feed", "assign", "unassign")
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-detail-ru", h.screen, listOf(588, 380)) }
            h.click("skills")
            h.screen.id shouldBe "civs.worker-skills"
            h.body() shouldContain "13/20 опыта"
            h.body() shouldContain "Ур. 5 · максимум"
            h.body() shouldContain "максимум 28"
            h.body() shouldContain "Писарь"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-skills-ru", h.screen, listOf(588, 380)) }
            h.click("history")
            h.screen.id shouldBe "civs.worker"
            h.click("inventory")
            h.ui.showingDialog(h.player) shouldBe false
            h.player.openInventory.topInventory.size shouldBe 54
            h.player.openInventory.topInventory.getItem(6)!!.type shouldBe Material.IRON_SHOVEL
            h.player.openInventory.topInventory.getItem(2)!!.type shouldBe Material.IRON_CHESTPLATE
            h.player.openInventory.topInventory.getItem(18)!!.amount shouldBe 2
            h.nativeClick(45); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.worker"
            verify(exactly = 1) { h.player.closeDialog() }
        }
    }

    test("town hall scribes show real book inputs workplace capacity and a distinct skill") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hall = base.buildings.first()
            h.city = base.copy(workers = base.workers.mapIndexed { index, worker ->
                // A random builder inclination is legitimate; keep this profession assertion deterministic.
                if (index in 1..2) worker.copy(id = if (index == 1) UUID(0, 1) else worker.id,
                    name = if (index == 1) "Матвей" else "Арина", assignment = hall.id,
                    nextDeliveryAt = 60_000, skills = if (index == 1) mapOf("TOWN_HALL" to 17L) else null) else worker
            }, inventory = base.inventory!!.copy(materials = mapOf("PAPER" to 6L)))
            every { h.host.buildingStatus(hall.id) } returns "building-status.waiting-inputs"
            every { h.host.workerStatus(any()) } returns "workers.status.waiting-inputs"
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, id, h.screen, listOf(588, 380)) } }
            h.ui.openBuilding(h.player, base.id, hall.id)
            h.body() shouldContain "2 / 2"
            h.body() shouldContain "Ждёт сырьё на складе"
            h.body() shouldContain "Книга"
            export("scribe-hall-ru")
            h.click("production")
            h.screen.id shouldBe "civs.production"
            h.body() shouldContain "Бумага"
            h.body() shouldContain "6 / 3 нужно"
            h.body() shouldContain "Кожа"
            h.body() shouldContain "0 / 1 нужно"
            h.body() shouldContain "Книга × 1"
            h.screen.buttons shouldBe emptyList()
            export("scribe-recipe-ru")
            h.click("history")
            h.click("workers")
            h.body() shouldContain "2 / 2"
            h.screen.buttons.size shouldBe 2
            h.screen.buttons.all { plainText(it.label).contains("освободить") } shouldBe true
            export("scribe-workers-ru")
            h.click("history")
            h.body() shouldContain "2 → 3"
            h.body() shouldContain "8 → 12"
            export("scribe-upgrade-ru")
            val scribe = h.city!!.workers[1]
            h.ui.openWorker(h.player, base.id, scribe.id)
            h.body() shouldContain "Писарь"
            h.body().contains("Строитель") shouldBe false
            h.click("skills")
            h.body() shouldContain "17/20 опыта"
            export("scribe-skills-ru")
            val hut = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            h.city = h.city!!.copy(buildings = h.city!!.buildings.map { when (it.id) {
                hut.id -> it.copy(level = 2)
                hall.id -> it.copy(level = 3)
                else -> it
            } })
            h.ui.openBuilding(h.player, base.id, hut.id)
            h.body() shouldContain "1 / 2"
            h.body() shouldContain "2 → 3"
            h.body() shouldContain "Дополнительный строитель"
            export("builders-upgrade-ru")
            verify(exactly = 0) { h.host.setProduction(any(), any(), any()) }
        }
    }

    test("infrastructure is compact in building lists and never consumes ordinary catalog or storage slots") {
        for (language in listOf("ru", "en")) withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val base = settlement(h.player)
            val template = base.buildings.last()
            val infrastructure = (0 until 20).map { i -> template.copy(id = UUID(90, i.toLong()), level = 1,
                type = when { i < 12 -> BuildingType.ROAD; i < 18 -> BuildingType.WALL; else -> BuildingType.GATE },
                control = null, phase = if (i == 19) BuildingPhase.CONSTRUCTING else BuildingPhase.ACTIVE) }
            h.city = base.copy(buildings = base.buildings + infrastructure)
            h.open()
            h.body() shouldContain "${base.buildings.size} / ${CivsRules.MAX_BUILDINGS}"
            h.click("buildings")
            h.screen.buttons.count { it.id.value.startsWith("building_") } shouldBe base.buildings.size
            h.screen.buttons.map { it.id.value } shouldNotContain "next"
            h.body() shouldContain "12 / 12"
            h.body() shouldContain "6 / 6"
            h.body() shouldContain "1 / 2"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "buildings-infrastructure-$language", h.screen) }
            h.click("catalog")
            h.screen.buttons.count { it.id.value.startsWith("offer_") } shouldBe 16
            val labels = h.screen.buttons.map { plainText(it.label) }
            for (type in infrastructure.map { it.type }.distinct()) {
                val name = plainText(CivsText.fromYaml(civsLocaleSource()).building(h.player, type))
                labels.any { it == "$name ›" } shouldBe false
            }
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "catalog-expanded-$language", h.screen) }
            h.open(); h.click("management"); h.click("stock")
            h.screen.buttons.count { it.id.value.startsWith("warehouse_") } shouldBe base.buildings.size
            h.ui.openBuilding(h.player, base.id, infrastructure.first().id)
            h.screen.buttons shouldBe emptyList()
            verify(exactly = 0) { h.host.buildingRequest(h.player, infrastructure.first().id) }
            for (type in listOf(BuildingType.TAVERN, BuildingType.BAKERY, BuildingType.GUARD_TOWER)) {
                h.city = base.copy(buildings = base.buildings.dropLast(1) + template.copy(type = type))
                h.ui.openBuilding(h.player, base.id, template.id)
                h.body().contains("building-purpose.") shouldBe false
                System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "new-building-${type.name.lowercase()}-$language", h.screen) }
            }
        }
    }

    test("new workshop catalog recipes and skill pages expose actual choices and return to their resident") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val smith = base.buildings.last().copy(type = BuildingType.BLACKSMITH, level = 2)
            h.city = base.copy(buildings = base.buildings.map { when {
                it.id == smith.id -> smith
                it.type == BuildingType.TOWN_HALL -> it.copy(level = 3)
                else -> it
            } }, inventory = base.inventory!!.copy(materials = mapOf("IRON_INGOT" to 3L, "STICK" to 4L)))
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, id, h.screen, listOf(588, 380)) } }
            h.open(); h.click("buildings"); h.click("catalog")
            val offers = h.screen.buttons.filter { it.id.value.startsWith("offer_") }
            offers.size shouldBe 16
            h.screen.columns shouldBe 2
            offers.map { it.label.color() }.distinct().size shouldBe 16
            h.screen.buttons.map { it.id.value } shouldContain "next"
            export("workshops-catalog-first-ru")
            offers.take(14).takeLast(4).map { plainText(it.label) } shouldBe
                listOf("Таверна ›", "Пекарня ›", "Сторожевая башня ›", "План поселения ›")
            h.click("offer_7")
            h.body() shouldContain "1 → 2 → 3"
            h.body() shouldContain "желез"
            export("workshops-smith-offer-ru")
            every { h.host.setProduction(any(), any(), any()) } answers {
                h.city = h.city!!.copy(buildings = h.city!!.buildings.map {
                    if (it.id == smith.id) it.copy(production = thirdArg()) else it })
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openBuilding(h.player, base.id, smith.id); h.click("production")
            h.body() shouldContain "3 / 1 нужно"
            h.body() shouldContain "4 / 2 нужно"
            h.screen.buttons.count { it.id.value.startsWith("production_") } shouldBe 4
            h.click("production_2"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.setProduction(h.player, smith.id, "IRON_PICKAXE") }
            h.body() shouldContain "3 / 3 нужно"
            h.body() shouldContain "Железная кирка × 1"
            export("workshops-smith-recipe-ru")
            h.click("history")
            h.body() shouldContain "2 → 3"
            h.body() shouldContain "рецепт и время"
            export("workshops-smith-upgrade-ru")
            val worker = base.workers.first()
            h.ui.openWorker(h.player, base.id, worker.id); h.click("skills")
            h.body() shouldContain "Плотник"
            h.body().contains("Библиотекарь") shouldBe false
            export("workshops-skills-first-ru")
            h.click("next")
            h.body() shouldContain "Кузнец"
            h.body() shouldContain "Каменотёс"
            h.body() shouldContain "Библиотекарь"
            export("workshops-skills-last-ru")
            h.click("previous")
            h.click("history")
            h.screen.id shouldBe "civs.worker"
        }
    }

    test("warehouse request board preserves remaining deficits without querying shared stock") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hall = base.buildings.first()
            val warehouse = base.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, level = 2)
            val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, hall.id, warehouse.entrance, "PAPER", 2)
            val courier = base.workers[1].copy(name = "Кладовщик", assignment = warehouse.id, nextDeliveryAt = 1, cargo = cargo)
            h.city = base.copy(buildings = base.buildings + warehouse,
                workers = base.workers.map { if (it.id == courier.id) courier else it },
                inventory = base.inventory!!.copy(mode = CivStorageMode.PHYSICAL,
                    materials = emptyMap(), buildingMaterials = emptyMap()))
            var physical = mapOf("PAPER" to 47L, "LEATHER" to 12L, "STONE" to 128L, "OAK_LOG" to 4L)
            var stockSnapshots = 0
            every { h.host.availableMaterials(h.player) } answers { stockSnapshots++; physical }
            every { h.host.localMaterials(h.player, hall.id) } returns mapOf("PAPER" to 1L)
            every { h.host.supplyRequests(h.player) } returns listOf(
                CivSupplyRequest(hall.id, "PAPER", 3, 2), CivSupplyRequest(hall.id, "LEATHER", 1, 0),
                CivSupplyRequest(hall.id, "STONE", 8, 0, true), CivSupplyRequest(hall.id, "OAK_LOG", 4, 0, true))
            every { h.host.workerStatus(courier.id) } returns "workers.status.carrying-cargo"
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, id, h.screen, listOf(588, 380)) } }
            h.ui.openBuilding(h.player, base.id, warehouse.id)
            h.body() shouldContain "1 / 2"
            val beforeRequests = stockSnapshots
            h.click("logistics")
            h.screen.id shouldBe "civs.logistics"
            logisticsTableCells(h.screen).first() shouldBe listOf("Материал", "Не хватает")
            logisticsTableCells(h.screen).first { it.first() == "Бумага" } shouldBe listOf("Бумага", "1")
            logisticsTableCells(h.screen).first { it.first() == "Кожа" } shouldBe listOf("Кожа", "1")
            logisticsTableCells(h.screen).first { it.first() == "Камень" } shouldBe listOf("Камень", "8")
            stockSnapshots shouldBe beforeRequests
            h.screen.body.size shouldBe 1 // One card for all four requests from this hall.
            h.screen.buttons shouldBe emptyList()
            logisticsTableCells(h.screen).first { it.first() == "Дубовое бревно" } shouldBe listOf("Дубовое бревно", "4")
            export("logistics-requests-ru")
            physical = physical + ("OAK_LOG" to 6L)
            h.click("history")
            val beforeRefresh = stockSnapshots
            h.click("logistics")
            logisticsTableCells(h.screen).first { it.first() == "Дубовое бревно" } shouldBe listOf("Дубовое бревно", "4")
            stockSnapshots shouldBe beforeRefresh
            h.screen.buttons shouldBe emptyList()
            export("logistics-requests-refreshed-ru")
            h.click("history")
            h.screen.id shouldBe "civs.building"
            h.ui.openBuilding(h.player, base.id, hall.id)
            h.click("production")
            h.body() shouldContain "1 / 3 нужно"
            h.body() shouldContain "0 / 1 нужно"
            h.body().contains("47 /") shouldBe false
            export("logistics-local-inputs-ru")
            h.ui.openWorker(h.player, base.id, courier.id)
            h.body() shouldContain "Груз: Бумага × 2"
            h.body() shouldContain "Сначала доставит груз"
            h.screen.buttons.map { it.id.value }.contains("assign") shouldBe true
            export("logistics-carrier-ru")
            h.click("logistics")
            h.screen.id shouldBe "civs.logistics"
            h.click("history")
            h.screen.id shouldBe "civs.worker"
            h.click("skills"); h.click("next")
            h.body() shouldContain "Кладовщик"
            export("logistics-skills-last-ru")
        }
    }

    test("courier board pages a long source locally and keeps short sources together without losing exact values") {
        for (locale in listOf("ru", "en")) withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag(locale))
            val labels = CivsText.fromYaml(civsLocaleSource())
            val base = settlement(h.player)
            val warehouse = base.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)
            val sources = (0..3).map { index -> base.buildings.last().copy(id = UUID.randomUUID(),
                type = BuildingType.BUILDER_HUT, level = 2, control = CivPosition(12 + index * 10, 64, 8)) }
            h.city = base.copy(buildings = base.buildings + warehouse + sources)
            val materials = listOf("OAK_LOG", "COBBLESTONE", "GLASS", "SPRUCE_PLANKS", "OAK_STAIRS",
                "STRIPPED_DARK_OAK_LOG", "STONE_BRICKS", "IRON_BARS", "LANTERN", "CHEST", "TORCH", "LADDER",
                "POLISHED_BLACKSTONE_BRICKS", "DARK_OAK_TRAPDOOR", "SPRUCE_FENCE_GATE", "CHISELED_STONE_BRICKS",
                "MOSSY_COBBLESTONE_STAIRS", "DEEPSLATE_BRICK_WALL", "POLISHED_ANDESITE", "BIRCH_SLAB",
                "RED_STAINED_GLASS_PANE", "WAXED_CUT_COPPER_STAIRS", "SMOOTH_QUARTZ", "WHITE_TERRACOTTA")
            val dense = materials.mapIndexed { index, material ->
                CivSupplyRequest(sources[0].id, material, 12L + index, if (index % 3 == 0) 4 else 0, true)
            }
            every { h.host.supplyRequests(h.player) } returns listOf(dense.first(),
                CivSupplyRequest(sources[1].id, "PAPER", 7, 0)) + dense.drop(1) + listOf(
                CivSupplyRequest(sources[2].id, "LEATHER", 8, 0), CivSupplyRequest(sources[3].id, "COAL", 9, 0))
            every { h.host.availableMaterials(h.player) } returns mapOf("OAK_LOG" to 64L, "GLASS" to 37L, "PAPER" to 16L)
            fun export(name: String) = System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                exportUiScreen(it, "$name-$locale", h.screen, listOf(640, 360))
            }
            h.ui.openBuilding(h.player, base.id, warehouse.id); h.click("logistics")
            h.screen.body.single().width shouldBe 320
            h.body().codePoints().anyMatch { it in 0x100000..0x101FFF } shouldBe true
            val observed = mutableListOf<List<String>>()
            repeat(4) { materialPage ->
                h.screen.body.size shouldBe 1 // A long source stays one card; its six visible rows are explicit.
                h.body() shouldContain "12, 64, 8"
                h.body() shouldContain "${materialPage + 1}/4"
                h.body().contains("22, 64, 8") shouldBe false
                logisticsTableCells(h.screen).first() shouldBe if (locale == "ru")
                    listOf("Материал", "Не хватает")
                    else listOf("Material", "Missing")
                val rows = logisticsMaterialRows(h.screen)
                rows.size shouldBe 6
                observed += rows
                h.screen.buttons.map { it.id.value } shouldBe buildList {
                    if (materialPage > 0) add("materials_previous")
                    if (materialPage < 3) add("materials_next")
                    add("next")
                }
                when (materialPage) {
                    0 -> export("logistics-grouped-dense")
                    1 -> export("logistics-grouped-materials-middle")
                    3 -> export("logistics-grouped-materials-last")
                }
                if (materialPage < 3) h.click("materials_next")
            }
            observed shouldBe materials.mapIndexed { index, material ->
                val transit = if (index % 3 == 0) 4 else 0
                listOf(plainText(labels.material(h.player, material)), (12 + index - transit).toString())
            }
            h.click("materials_previous")
            h.body() shouldContain "3/4"
            logisticsMaterialRows(h.screen) shouldBe observed.subList(12, 18)
            h.click("materials_next")
            h.click("next")
            h.screen.body.size shouldBe 2
            val shortCards = h.screen.body.map { plainText(it.text) }
            shortCards[0] shouldContain "22, 64, 8"
            shortCards[0] shouldContain plainText(labels.material(h.player, "PAPER"))
            shortCards[1] shouldContain "32, 64, 8"
            shortCards[1] shouldContain plainText(labels.material(h.player, "LEATHER"))
            h.screen.buttons.map { it.id.value } shouldBe listOf("previous", "next")
            export("logistics-grouped-short")
            h.click("previous")
            h.screen.body.size shouldBe 1
            h.body() shouldContain "4/4" // Switching sources retains the source's local material page.
            logisticsMaterialRows(h.screen) shouldBe observed.takeLast(6)
            h.click("next"); h.click("next")
            h.screen.body.size shouldBe 1
            h.body() shouldContain "42, 64, 8"
            h.body() shouldContain plainText(labels.material(h.player, "COAL"))
            h.screen.buttons.map { it.id.value } shouldBe listOf("previous")
            export("logistics-grouped-last")
            h.click("history")
            h.screen.id shouldBe "civs.building"
        }
    }

    test("warehouse request board renders deficits without a shared stock snapshot") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hall = base.buildings.first()
            val warehouse = base.buildings.last().copy(type = BuildingType.WAREHOUSE)
            h.city = base.copy(buildings = listOf(hall, warehouse))
            every { h.host.supplyRequests(h.player) } returns listOf(CivSupplyRequest(hall.id, "PAPER", 3, 0))
            every { h.host.availableMaterials(h.player) } throws CivsRejection("storage-unavailable")
            h.ui.openBuilding(h.player, base.id, warehouse.id)
            h.click("logistics")
            logisticsTableCells(h.screen).first { it.first() == "Бумага" } shouldBe listOf("Бумага", "3")
            h.body().contains("Нет данных") shouldBe false
            h.body().contains("Неизвестно") shouldBe false
            h.body().contains("3 / 0") shouldBe false
            h.screen.buttons shouldBe emptyList()
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "logistics-stock-unavailable-ru", h.screen, listOf(588, 380)) }
        }
    }

    test("courier board clamps covered requests with and without pack fonts") {
        for (font in listOf("", "arc:dialog_ui")) withUi(decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val warehouse = base.buildings.last().copy(type = BuildingType.WAREHOUSE)
            h.city = base.copy(buildings = base.buildings.dropLast(1) + warehouse)
            every { h.host.supplyRequests(h.player) } returns listOf(
                CivSupplyRequest(base.buildings.first().id, "PAPER", 3, 5),
                CivSupplyRequest(base.buildings.first().id, "LEATHER", 2, 0))
            every { h.host.availableMaterials(h.player) } returns emptyMap()
            h.ui.openBuilding(h.player, base.id, warehouse.id); h.click("logistics")
            logisticsTableCells(h.screen).first { it.first() == "Бумага" } shouldBe listOf("Бумага", "0")
            logisticsTableCells(h.screen).first { it.first() == "Кожа" } shouldBe listOf("Кожа", "2")
            logisticsTableCells(h.screen).drop(1).none { it.getOrNull(1)?.startsWith("-") == true } shouldBe true
            h.body().codePoints().anyMatch { it in 0x100000..0x101FFF } shouldBe font.isNotEmpty()
            if (font.isEmpty()) h.body().codePoints().anyMatch { Character.getType(it) == Character.PRIVATE_USE.toInt() } shouldBe false
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "logistics-covered-and-short-ru", h.screen, listOf(588, 380)) }
        }
    }

    test("carpentry exposes automatic orders and inspectable tiers without manual production mutation") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val workshop = base.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.SAWMILL, level = 1)
            h.city = base.copy(buildings = base.buildings + workshop)
            val order = CivCarpentryOrderView(base.workers.first().id, "Александра", "STRIPPED_DARK_OAK_LOG", 1,
                mapOf("DARK_OAK_LOG" to 1L), mapOf("DARK_OAK_LOG" to 1L), "DARK_OAK_STAIRS", base.buildings.first().id,
                "waiting-materials")
            val recipes = CivsCarpentry.views.filter { it.material in setOf("OAK_PLANKS", "STRIPPED_DARK_OAK_LOG",
                "CHEST", "BARREL", "CAMPFIRE", "SCAFFOLDING", "BOOKSHELF") }
            var view = CivCarpentryView(1, false, false, listOf(order), recipes)
            every { h.host.carpentry(h.player, workshop.id) } answers { view }
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, id, h.screen, listOf(588, 380)) } }
            h.ui.openBuilding(h.player, base.id, workshop.id); h.click("production")
            h.screen.id shouldBe "civs.production"
            h.body() shouldContain "Александра"
            h.body() shouldContain "Ждёт материалы"
            h.body() shouldContain "Не хватает:"
            h.body() shouldContain "Для заявки:"
            h.screen.buttons.map { it.id.value } shouldBe listOf("recipes")
            export("carpentry-order-missing-ru")
            view = view.copy(orders = listOf(order.copy(missing = emptyMap(), status = "crafting")))
            h.click("recipes"); h.click("history")
            h.body() shouldContain "Изготавливает"
            h.body().contains("Не хватает:") shouldBe false
            export("carpentry-order-working-ru")
            h.click("recipes")
            h.body() shouldContain "Обтёсывание"
            export("carpentry-tier-1-ru")
            h.click("tier_2")
            h.screen.id shouldBe "civs.carpentry-recipes"
            h.body() shouldContain "улучшите мастерскую до уровня 2"
            export("carpentry-tier-locked-ru")
            h.click("next")
            h.body() shouldContain "Бамбук"
            export("carpentry-tier-locked-page-2-ru")
            h.click("history")
            h.screen.id shouldBe "civs.production"
            view = view.copy(orders = emptyList(), planning = true)
            h.click("recipes"); h.click("history"); h.body() shouldContain "Подбираем изделия"
            view = view.copy(planning = false)
            h.click("recipes"); h.click("history"); h.body() shouldContain "Подходящих заказов пока нет"
            export("carpentry-waiting-ru")
            view = view.copy(paused = true)
            h.click("recipes"); h.click("history"); h.body() shouldContain "Работа приостановлена"
            export("carpentry-paused-ru")
            h.player.setLocale(Locale.US)
            view = view.copy(paused = false, orders = listOf(order))
            h.click("recipes"); h.click("history"); export("carpentry-order-missing-en")
            verify(exactly = 0) { h.host.setProduction(any(), any(), any()); h.player.closeDialog() }
        }
    }

    test("scribe ingredient lookup failures display unknown stock without inventing a shortage") {
        withUi { h ->
            h.city = settlement(h.player)
            every { h.host.availableMaterials(any()) } throws CivsRejection("storage-unavailable")
            h.ui.openBuilding(h.player, h.city!!.id, h.city!!.buildings.first().id)
            h.click("production")
            h.body() shouldContain "Unavailable / 3 needed"
            h.body().contains("0 / 3 needed") shouldBe false
            h.screen.buttons shouldBe emptyList()
        }
    }

    test("resident feeding remains a guarded worker action beside native equipment") {
        withUi { h ->
            val base = settlement(h.player)
            val worker = base.workers.first().copy(health = 8.0, inventory = mapOf("BREAD" to 1L))
            h.city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it })
            every { h.host.feedWorker(h.player, worker.id) } answers {
                h.city = CivsRules.feedResident(h.city!!, h.player.uniqueId, worker.id)
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openWorker(h.player, base.id, worker.id)
            val before = h.screen
            h.click("feed"); h.click("feed", screen = before); h.paper.performTicks(2)
            h.body() shouldContain "\uE601 14/20"
            h.screen.buttons.map { it.id.value } shouldNotContain "feed"
            verify(exactly = 1) { h.host.feedWorker(h.player, worker.id) }
        }
    }

    test("native resident inventory delegates exact slot clicks and rejects a second pending click") {
        withUi { h ->
            val base = settlement(h.player)
            val worker = base.workers.first().copy(inventory = mapOf("BREAD" to 8L))
            h.city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it })
            val pending = CompletableFuture<Unit>()
            every { h.host.transferResidentItem(h.player, worker.id, 6, null, ClickType.SHIFT_LEFT) } returns pending
            h.ui.openWorker(h.player, base.id, worker.id)
            h.click("inventory")
            val view = h.player.openInventory.topInventory
            h.nativeClick(18, ClickType.SHIFT_LEFT).isCancelled shouldBe true
            h.nativeClick(18, ClickType.SHIFT_LEFT)
            h.player.openInventory.topInventory shouldBe view
            view.getItem(49)!!.type shouldBe Material.CLOCK
            verify(exactly = 1) { h.host.transferResidentItem(h.player, worker.id, 6, null, ClickType.SHIFT_LEFT) }
            pending.complete(Unit); h.paper.performTicks(2)
            h.player.inventory.setItem(9, ItemStack(Material.IRON_AXE))
            h.nativeClick(54)
            verify(exactly = 1) { h.host.transferResidentItem(h.player, worker.id, null, 9, ClickType.LEFT) }
            verify(exactly = 0) { h.host.supplyWorker(any(), any(), any()); h.host.returnWorkerItem(any(), any(), any()) }
        }
    }

    test("corpse inventory identifies the deceased and killer and only transfers belongings out") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val worker = base.workers.first().copy(name = "Василий", health = 0.0)
            val corpse = CivCorpse(worker, base.center, 1, Long.MAX_VALUE, "<red>Mayor", "PLAYER", "ENTITY_ATTACK")
            h.city = base.copy(workers = base.workers.filterNot { it.id == worker.id }, corpses = listOf(corpse))
            every { h.host.corpseItems(h.player, base.id, worker.id) } returns List(33) { if (it == 4) ItemStack(Material.IRON_PICKAXE) else null }
            every { h.host.transferCorpseItem(h.player, base.id, worker.id, 4, ClickType.LEFT) } returns CompletableFuture()
            h.ui.openCorpse(h.player, base.id, worker.id)
            val view = h.player.openInventory.topInventory
            plainText(h.player.openInventory.title()) shouldBe "Погибший · Василий"
            fun details() = plainText(view.getItem(49)!!.itemMeta.lore().orEmpty().fold(Component.empty(), Component::append))
            details() shouldContain "Убийца: <red>Mayor"
            details().contains("Кликните свой предмет") shouldBe false
            h.player.inventory.setItem(9, ItemStack(Material.DIAMOND))
            h.nativeClick(54).isCancelled shouldBe true
            h.player.setItemOnCursor(ItemStack(Material.DIAMOND))
            h.nativeClick(18).isCancelled shouldBe true
            verify(exactly = 0) { h.host.transferResidentItem(any(), any(), any(), any(), any()); h.host.transferCorpseItem(any(), any(), any(), any(), any()) }
            h.player.setItemOnCursor(null)
            h.nativeClick(6)
            h.nativeClick(6)
            verify(exactly = 1) { h.host.transferCorpseItem(h.player, base.id, worker.id, 4, ClickType.LEFT) }

            for ((changed, expected) in listOf(
                corpse.copy(killerName = null, killerType = "ZOMBIE") to "Убийца: Зомби",
                corpse.copy(killerName = null, killerType = null, cause = "FALL") to "Причина смерти: Падение",
            )) {
                h.city = h.city!!.copy(corpses = listOf(changed))
                h.ui.openCorpse(h.player, base.id, worker.id)
                plainText(h.player.openInventory.topInventory.getItem(49)!!.itemMeta.lore().orEmpty()
                    .fold(Component.empty(), Component::append)) shouldContain expected
            }
            h.city = h.city!!.copy(corpses = listOf(corpse.copy(worker = worker.copy(name = "Анна", sex = CivSex.FEMALE))))
            h.ui.openCorpse(h.player, base.id, worker.id)
            plainText(h.player.openInventory.title()) shouldBe "Погибшая · Анна"
        }
    }

    test("Auto corpse inventory requires admin and an old menu cannot take a later death's items") {
        withUi { h ->
            val base = autonomousSettlement(h.player, "Auto")
            val worker = base.workers.first().copy(health = 0.0)
            val corpse = CivCorpse(worker, base.center, 100, Long.MAX_VALUE, null, null, "FALL")
            h.city = base.copy(workers = base.workers.drop(1), corpses = listOf(corpse))
            every { h.host.corpseItems(h.player, base.id, worker.id) } returns
                List(33) { if (it == 4) ItemStack(Material.IRON_PICKAXE) else null }
            h.ui.openCorpse(h.player, base.id, worker.id)
            verify(exactly = 0) { h.host.corpseItems(any(), any(), any()) }
            h.player.addAttachment(h.plugin, "arccivs.admin", true)
            h.ui.openCorpse(h.player, base.id, worker.id)
            h.player.openInventory.topInventory.getItem(6)!!.type shouldBe Material.IRON_PICKAXE
            h.city = h.city!!.copy(corpses = listOf(corpse.copy(diedAt = 200)))
            h.nativeClick(6).isCancelled shouldBe true
            verify(exactly = 0) { h.host.transferCorpseItem(any(), any(), any(), any(), any()) }
        }
    }

    test("worker list and detail preserve concrete source-owned reasons") {
        withUi { h ->
            h.city = settlement(h.player)
            val worker = h.city!!.workers.last()
            h.city = CivsRules.assign(h.city!!, h.player.uniqueId, worker.id, h.city!!.buildings.last().id, 0)
            for ((status, label) in listOf(
                "no-construction" to "Walking around · no construction",
                "sleeping" to "Sleeping",
                "route-unreachable" to "No accessible route",
                "waiting-materials" to "Waiting for construction materials",
                "collecting-supplies" to "Collecting supplies",
                "placing" to "Placing the next section",
                "working" to "Working",
                "injury-pending" to "Injured · saving condition",
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

    test("empty warehouses and an active construction do not block marking another building site") {
        withUi { h ->
            val base = settlement(h.player)
            h.city = base.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                construction = CivConstruction(UUID.randomUUID(), base.buildings.last().id, 1, 100, 20,
                    ConstructionPhase.CONSTRUCTING, Stock(), payment = CivConstructionPayment()))
            h.open(); h.click("management"); h.click("stock")
            h.body() shouldContain "Each building has its own warehouse"
            h.click("warehouse_0")
            h.body() shouldContain "Nothing stored yet"
            h.screen.buttons.map { it.id.value } shouldBe listOf("intake", "deposit")
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_2")
            h.screen.buttons.map { it.id.value } shouldBe listOf("prepare")
            h.body() shouldContain "create a control barrel and temporary fence"
            every { h.host.beginBuildingSelection(h.player, BuildingType.HOUSE, "medieval-oak") } returns CompletableFuture.completedFuture(Unit)
            h.click("prepare"); h.click("style_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.beginBuildingSelection(h.player, BuildingType.HOUSE, "medieval-oak") }
            verify(exactly = 0) { h.host.startBuilding(any(), any()); h.host.availableMaterials(any()) }
        }
    }

    test("background city saving keeps building previews and queued site starts available") {
        withUi { h ->
            val base = settlement(h.player)
            h.city = base
            every { h.host.cityBusy(any()) } returns true
            every { h.host.beginBuildingSelection(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_2")
            h.screen.buttons.map { it.id.value } shouldBe listOf("prepare")
            h.body().contains("temporarily unavailable") shouldBe false
            h.click("prepare"); h.click("style_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.beginBuildingSelection(h.player, BuildingType.HOUSE, "medieval-oak") }
            val building = base.buildings.last()
            every { h.host.upgradeBuilding(h.player, building.id) } returns CompletableFuture.completedFuture(Unit)
            h.ui.openBuilding(h.player, base.id, building.id); h.click("upgrade"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.building"
            h.body().contains("temporarily unavailable") shouldBe false
            verify(exactly = 1) { h.host.upgradeBuilding(h.player, building.id) }
            val site = building.copy(phase = BuildingPhase.SITE)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == site.id) site else it })
            every { h.host.availableMaterials(h.player) } throws CivsRejection("storage-busy")
            h.ui.openBuilding(h.player, base.id, site.id); h.click("materials"); h.paper.performTicks(2)
            h.screen.buttons.map { it.id.value } shouldBe listOf("start")
            val before = h.screen
            h.click("start"); h.click("start", screen = before); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.startBuilding(h.player, site.id) }
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

    test("relocation retains errors and allows unrelated construction but blocks busy or same-building changes") {
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
            h.body() shouldContain "Settlement changes are temporarily unavailable"
            every { h.host.cityBusy(h.player) } returns false
            val constructionLookup = CompletableFuture<CivPosition>()
            every { h.host.buildingControl(h.player, old.id) } returns constructionLookup
            h.ui.openBuilding(h.player, base.id, old.id); h.click("relocate_control")
            h.city = h.city!!.copy(construction = CivConstruction(UUID.randomUUID(), base.buildings.first().id,
                3, 100, 20, ConstructionPhase.CONSTRUCTING, Stock()))
            constructionLookup.complete(target); h.paper.performTicks(2)
            h.screen.buttons.any { it.id.value == "relocate" } shouldBe true
            h.ui.openBuilding(h.player, base.id, old.id)
            h.screen.buttons.any { it.id.value == "relocate_control" } shouldBe true
            val ownConstructionLookup = CompletableFuture<CivPosition>()
            every { h.host.buildingControl(h.player, old.id) } returns ownConstructionLookup
            h.click("relocate_control")
            h.city = h.city!!.copy(construction = h.city!!.construction!!.copy(buildingId = old.id))
            ownConstructionLookup.complete(target); h.paper.performTicks(2)
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
            every { h.host.buildingStatus(site.id) } returns "building-status.site"
            every { h.host.buildingMaterials(h.player, site.id) } returns CompletableFuture.completedFuture(mapOf("OAK_LOG" to 900L))
            every { h.host.availableMaterials(h.player) } returns emptyMap()
            val pending = CompletableFuture<Unit>()
            every { h.host.startBuilding(h.player, site.id) } returns pending
            h.ui.openBuilding(h.player, base.id, site.id)
            h.screen.id shouldBe "civs.building"
            h.body() shouldContain "Site marked out"
            h.click("materials"); h.paper.performTicks(2)
            h.body() shouldContain "Missing materials can be added later"
            materialTableCells(h.screen).last() shouldBe listOf("Oak Log", "900")
            val before = h.screen
            h.click("start"); h.click("start", screen = before)
            h.body() shouldBe "Preparing construction…"
            pending.complete(Unit)
            h.paper.performTicks(2)
            h.screen.id shouldBe "civs.building"
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
            every { h.host.localMaterials(h.player, building.id) } returns mapOf("COBBLESTONE" to 2L)
            h.open()
            h.body() shouldContain "refill storage so the builder can continue"
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.body() shouldContain "Waiting for materials"
            materialTableCells(h.screen).last() shouldBe listOf("Cobblestone", "17")
            h.body() shouldContain "A courier can deliver them"
            h.body().contains("up to") shouldBe false
            h.screen.buttons.map { it.id.value } shouldBe listOf("supply")
            h.city = h.city!!.copy(construction = order.copy(phase = ConstructionPhase.CONSTRUCTING,
                materials = mapOf("COBBLESTONE" to 3L)))
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            materialTableCells(h.screen).last() shouldBe listOf("Cobblestone", "1")
            h.body().contains("Waiting for materials") shouldBe false
            h.city = h.city!!.copy(construction = order.copy(payment = null))
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.body() shouldContain "already paid"
            h.screen.buttons shouldBe emptyList()
            verify(exactly = 0) { h.host.buildingMaterials(any(), any()); h.host.startBuilding(any(), any()); h.host.retryConstruction(any(), any()) }
        }
    }

    test("construction materials use a framed localized table and preserve exact counts") {
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
            materialTableCells(h.screen).first() shouldBe listOf("Материал", "Не хватает")
            h.body() shouldContain "Булыжные ступеньки"
            materialTableCells(h.screen).single { it.first() == "Булыжник" } shouldBe listOf("Булыжник", "68")
            materialTableCells(h.screen).single { it.first() == "Земля" } shouldBe listOf("Земля", "516")
            h.body() shouldContain "\uE580"
            h.body().contains("Cobblestone") shouldBe false
            h.body().contains("Еловые доски") shouldBe false
            h.body().contains("9000001") shouldBe false
            h.body().contains("2000003") shouldBe false
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "construction-deficit-ru", h.screen, listOf(588, 380)) }
            h.screen.buttons.map { it.id.value } shouldBe listOf("supply", "start")
            verify(exactly = 1) { h.host.buildingMaterials(h.player, site.id) }
            verify(exactly = 0) { h.host.startBuilding(any(), any()) }
        }
    }

    test("covered construction materials show a clear complete state without zero rows or supply actions") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hut = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            val target = base.buildings.last()
            h.city = base.copy(construction = CivConstruction(UUID.randomUUID(), target.id, 2, 100, 20,
                ConstructionPhase.CONSTRUCTING, Stock(), materials = mapOf("COBBLESTONE" to 20L), payment = CivConstructionPayment()))
            every { h.host.constructionAvailable(h.player, target.id) } returns mapOf("COBBLESTONE" to 25L)
            h.ui.openBuilding(h.player, base.id, hut.id); h.paper.performTicks(2)
            h.body() shouldContain "Все нужные материалы уже у строителей."
            h.body().contains("Булыжник") shouldBe false
            h.screen.buttons.map { it.id.value } shouldNotContain "supply"
            h.screen.buttons.map { it.id.value } shouldNotContain "grant"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "builder-materials-covered-ru", h.screen, listOf(588, 380)) }
            h.ui.openBuilding(h.player, base.id, target.id); h.click("materials"); h.paper.performTicks(2)
            h.body() shouldContain "Все нужные материалы уже у строителей."
            h.body().contains("Булыжник") shouldBe false
            h.screen.buttons shouldBe emptyList()

            every { h.host.constructionAvailable(h.player, target.id) } throws CivsRejection("storage-busy")
            h.ui.openBuilding(h.player, base.id, target.id); h.click("materials"); h.paper.performTicks(2)
            h.body().contains("Все нужные материалы уже у строителей.") shouldBe false
            verify(exactly = 0) { h.host.supplyConstruction(any(), any()); h.host.supplyConstruction(any(), any(), any()) }
        }
    }

    test("builder hut opens the current bill with eight localized rows and stable available and missing actions") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hut = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            val target = base.buildings.last()
            val order = CivConstruction(UUID.randomUUID(), target.id, 2, 475, 40,
                ConstructionPhase.WAITING_MATERIALS, Stock(), materials = builderMaterialEstimate(), payment = CivConstructionPayment())
            h.city = base.copy(construction = order, constructionQueue = listOf(base.buildings[1].id))
            every { h.host.constructionAvailable(h.player, target.id) } returns mapOf("COBBLESTONE" to 3L)
            h.player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 64))
            h.player.inventory.setItem(1, ItemStack(Material.COBBLESTONE, 12).apply {
                editMeta { it.displayName(Component.text("Сувенир")) }
            })
            fun openMaterials() { h.ui.openBuilding(h.player, base.id, hut.id); h.paper.performTicks(2) }
            fun tableText() = plainText(h.screen.body.single { plainText(it.text).contains('\uE580') }.text)
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, id, h.screen, listOf(588, 380)) } }

            openMaterials()
            h.screen.id shouldBe "civs.builder-materials"
            h.body() shouldContain "Строим: Лесорубка · ур. 2 · 40/475"
            h.body() shouldContain "Припасы попадут в этот дом строителя"
            h.body() shouldContain "В очереди: 1"
            materialTableCells(h.screen).size shouldBe 9
            materialTableCells(h.screen).first() shouldBe listOf("Материал", "Не хватает")
            tableText() shouldContain "Резная книжная полка"
            materialTableCells(h.screen).single { it.first() == "Булыжник" } shouldBe listOf("Булыжник", "1")
            tableText().contains(" / ") shouldBe false
            tableText().contains("Факел") shouldBe false
            h.screen.buttons.single { it.id.value == "supply" }.label.color() shouldBe TextColor.color(0x9BD48D)
            export("builder-hut-materials-ready-ru")
            val firstPage = tableText()
            h.click("next"); h.paper.performTicks(2)
            materialTableCells(h.screen).size shouldBe 6
            tableText() shouldContain "Факел"
            materialTableCells(h.screen).last() shouldBe listOf("Факел", "34")
            export("builder-hut-materials-page-2-ru")
            h.click("previous"); h.paper.performTicks(2)
            tableText() shouldBe firstPage
            h.click("details")
            h.screen.id shouldBe "civs.building"
            h.click("history"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.builder-materials"

            h.player.inventory.clear()
            openMaterials()
            val missing = h.screen.buttons.single { it.id.value == "supply" }
            plainText(missing.label) shouldBe "Внести всё"
            missing.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(missing.tooltip) shouldBe "В вашем инвентаре нет недостающих материалов."
            export("builder-hut-materials-missing-ru")
            h.click("supply"); h.paper.performTicks(2)
            h.click("details")
            h.screen.id shouldBe "civs.building"
            verify(exactly = 0) { h.host.supplyConstruction(any(), any(), any()); h.host.supplyBuilderMaterials(any(), any()) }
            verify(exactly = 0) { h.host.buildingMaterials(h.player, hut.id) }
            h.city = base
            h.ui.openBuilding(h.player, base.id, hut.id)
            h.screen.id shouldBe "civs.building"
        }
    }

    test("builder bulk actions keep the clicked destination and suppress repeats through partial completion") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hut = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            val target = base.buildings.last()
            val worker = base.workers.first().copy(name = "Василий")
            h.city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it },
                construction = CivConstruction(UUID.randomUUID(), target.id, 2, 100, 20,
                    ConstructionPhase.CONSTRUCTING, Stock(), materials = mapOf("COBBLESTONE" to 20L), payment = CivConstructionPayment()))
            var stored = 2L
            every { h.host.constructionAvailable(h.player, target.id) } answers { mapOf("COBBLESTONE" to stored) }
            h.player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 5))
            val hutTransfer = CompletableFuture<CivsSupplyResult>()
            every { h.host.supplyConstruction(h.player, target.id, hut.id) } returns hutTransfer
            h.ui.openBuilding(h.player, base.id, hut.id); h.paper.performTicks(2)
            val before = h.screen
            val renderCount = h.screens.size
            val freshMaterials = CompletableFuture<Map<String, Long>>()
            every { h.host.buildingMaterials(h.player, target.id) } returns freshMaterials
            h.click("supply"); h.click("supply", screen = before)
            h.screens.size shouldBe renderCount
            h.screen shouldBe before
            stored = 7L
            h.player.inventory.clear()
            hutTransfer.complete(CivsSupplyResult(5L, 13L, false)); h.paper.performTicks(2)
            h.screens.size shouldBe renderCount
            h.screen shouldBe before
            h.click("supply", screen = before)
            freshMaterials.complete(mapOf("COBBLESTONE" to 20L)); h.paper.performTicks(2)
            h.screens.size shouldBe renderCount + 1
            materialTableCells(h.screen).last() shouldBe listOf("Булыжник", "13")
            h.body() shouldContain "Передано: 5. Ещё нужно: 13."
            h.body().contains("Освободите место и повторите.") shouldBe false
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "builder-hut-materials-partial-ru", h.screen, listOf(588, 380)) }
            verify(exactly = 1) { h.host.supplyConstruction(h.player, target.id, hut.id) }
            verify(exactly = 0) { h.host.supplyConstruction(any(), any()) }

            h.player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 4))
            val workerTransfer = CompletableFuture<CivsSupplyResult>()
            every { h.host.supplyBuilderMaterials(h.player, worker.id) } returns workerTransfer
            h.ui.openWorker(h.player, base.id, worker.id); h.paper.performTicks(2)
            h.body() shouldContain "Припасы получит Василий"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "builder-npc-materials-ready-ru", h.screen, listOf(588, 380)) }
            val workerBefore = h.screen
            h.click("supply"); h.click("supply", screen = workerBefore)
            h.dismiss()
            val count = h.screens.size
            workerTransfer.complete(CivsSupplyResult(4L, 9L, false)); h.paper.performTicks(2)
            h.screens.size shouldBe count
            h.player.nextComponentMessage() shouldBe null
            verify(exactly = 1) { h.host.supplyBuilderMaterials(h.player, worker.id) }
            verify(exactly = 0) { h.host.giveWorkerRequest(any(), any(), any()) }
        }
    }

    test("creative construction grants ignore empty player inventory and preserve hut and worker destinations") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.player.gameMode = GameMode.CREATIVE
            val base = settlement(h.player)
            val hut = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            val target = base.buildings.last()
            val worker = base.workers.first().copy(name = "Василий")
            h.city = base.copy(workers = base.workers.map { if (it.id == worker.id) worker else it },
                construction = CivConstruction(UUID.randomUUID(), target.id, 2, 100, 20,
                    ConstructionPhase.CONSTRUCTING, Stock(), materials = mapOf("COBBLESTONE" to 20L), payment = CivConstructionPayment()))
            every { h.player.hasPermission("arccivs.admin") } returns false
            every { h.host.constructionAvailable(h.player, target.id) } returns emptyMap()
            val pending = CompletableFuture<CivsSupplyResult>()
            every { h.host.grantConstructionMaterials(h.player, target.id, hut.id) } returns pending
            h.ui.openBuilding(h.player, base.id, hut.id); h.paper.performTicks(2)
            val grant = h.screen.buttons.single { it.id.value == "grant" }
            plainText(grant.label) shouldBe "Выдать всё"
            grant.label.color() shouldBe TextColor.color(0x9BD48D)
            materialTableCells(h.screen).last() shouldBe listOf("Булыжник", "20")
            h.screen.buttons.map { it.id.value } shouldNotContain "supply"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "builder-hut-materials-creative-ru", h.screen, listOf(588, 380)) }
            val before = h.screen
            h.click("grant"); h.click("grant", screen = before)
            pending.complete(CivsSupplyResult(20L, 0L, false)); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.grantConstructionMaterials(h.player, target.id, hut.id) }

            every { h.host.grantBuilderMaterials(h.player, worker.id) } returns CompletableFuture.completedFuture(CivsSupplyResult(4L, 16L, true))
            h.ui.openWorker(h.player, base.id, worker.id); h.paper.performTicks(2)
            h.screen.buttons.single { it.id.value == "grant" }.label.color() shouldBe TextColor.color(0x9BD48D)
            h.click("grant"); h.paper.performTicks(2)
            h.body() shouldContain "Передано: 4. Ещё нужно: 16."
            verify(exactly = 1) { h.host.grantBuilderMaterials(h.player, worker.id) }
            verify(exactly = 0) { h.host.supplyConstruction(any(), any()); h.host.supplyConstruction(any(), any(), any()); h.host.supplyBuilderMaterials(any(), any()) }
        }
    }

    test("construction supply refreshes stock once and admin grants report partial capacity") {
        withUi { h ->
            val base = settlement(h.player)
            val building = base.buildings.first()
            h.city = base.copy(construction = CivConstruction(UUID.randomUUID(), building.id, 3, 100, 40,
                ConstructionPhase.WAITING_MATERIALS, Stock(), materials = mapOf("COBBLESTONE" to 19L),
                payment = CivConstructionPayment()))
            var stored = 2L
            every { h.host.availableMaterials(h.player) } answers { mapOf("COBBLESTONE" to stored) }
            val pending = CompletableFuture<CivsSupplyResult>()
            every { h.host.supplyConstruction(h.player, building.id) } returns pending
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.screen.buttons.map { it.id.value } shouldBe listOf("supply")
            val before = h.screen
            val renderCount = h.screens.size
            h.click("supply"); h.click("supply", screen = before)
            h.screens.size shouldBe renderCount
            h.screen shouldBe before
            stored = 7L
            pending.complete(CivsSupplyResult(5L, 12L, false)); h.paper.performTicks(2)
            h.screens.size shouldBe renderCount + 1
            materialTableCells(h.screen).last() shouldBe listOf("Cobblestone", "12")
            h.body() shouldContain "Transferred: 5. Still needed: 12."
            h.body().contains("Free up space") shouldBe false
            verify(exactly = 1) { h.host.supplyConstruction(h.player, building.id) }
            verify(exactly = 0) { h.host.grantConstructionMaterials(any(), any()) }

            every { h.player.hasPermission("arccivs.admin") } returns true
            every { h.host.grantConstructionMaterials(h.player, building.id) } answers {
                stored = 11L
                CompletableFuture.completedFuture(CivsSupplyResult(4L, 8L, true))
            }
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials"); h.click("grant"); h.paper.performTicks(2)
            materialTableCells(h.screen).last() shouldBe listOf("Cobblestone", "8")
            h.body() shouldContain "Transferred: 4. Still needed: 8."
            h.body() shouldContain "Free up space and try again."
            verify(exactly = 1) { h.host.grantConstructionMaterials(h.player, building.id) }

            every { h.host.cityBusy(h.player) } returns true
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.screen.buttons shouldBe emptyList()
            every { h.host.cityBusy(h.player) } returns false
            stored = 19L
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            h.screen.buttons shouldBe emptyList()
            h.body() shouldContain "The builders already have all required materials."
        }
    }

    test("supply failure stays on materials and dismissed supply completion cannot reopen it") {
        withUi { h ->
            val base = settlement(h.player)
            val building = base.buildings.first()
            h.city = base.copy(construction = CivConstruction(UUID.randomUUID(), building.id, 3, 100, 40,
                ConstructionPhase.CONSTRUCTING, Stock(), materials = mapOf("COBBLESTONE" to 19L),
                payment = CivConstructionPayment()))
            every { h.host.availableMaterials(h.player) } returns emptyMap()
            val failedTransfer = CompletableFuture<CivsSupplyResult>()
            every { h.host.supplyConstruction(h.player, building.id) } returns failedTransfer
            h.ui.openBuilding(h.player, base.id, building.id); h.click("materials")
            val before = h.screen
            val renderCount = h.screens.size
            h.click("supply")
            h.screens.size shouldBe renderCount
            h.screen shouldBe before
            failedTransfer.completeExceptionally(CivsRejection("construction-supplies-empty")); h.paper.performTicks(2)
            h.screens.size shouldBe renderCount + 1
            h.screen.id shouldBe "civs.building-materials"
            h.body() shouldContain "Your inventory has no plain items needed for this construction."
            materialTableCells(h.screen).last() shouldBe listOf("Cobblestone", "19")
            val pending = CompletableFuture<CivsSupplyResult>()
            every { h.host.supplyConstruction(h.player, building.id) } returns pending
            h.click("supply")
            h.dismiss()
            val count = h.screens.size
            pending.complete(CivsSupplyResult(2L, 17L, false)); h.paper.performTicks(2)
            h.screens.size shouldBe count
            h.ui.showingDialog(h.player) shouldBe false
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
            h.body() shouldContain "Settlement changes are temporarily unavailable"
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
            h.body().contains("temporarily unavailable") shouldBe false
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
            h.body() shouldContain "keeps one builder workplace"
            h.screen.buttons.map { it.id.value }.contains("workers") shouldBe true
            h.screen.buttons.map { it.id.value }.contains("production") shouldBe false
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
            h.body() shouldContain "first five residents"
            h.screen.buttons.map { it.id.value } shouldNotContain "recruit"
            h.city = base
            val idle = base.workers[1]
            every { h.host.workerStatus(idle.id) } returns "workers.status.sleeping"
            h.ui.openWorker(h.player, base.id, idle.id)
            h.body() shouldContain "Sleeping"
            every { h.host.workerStatus(idle.id) } returns "workers.status.unassigned"
            h.ui.openWorker(h.player, base.id, idle.id)
            h.body() shouldContain "No assignment"
            for (language in listOf("ru", "en")) {
                h.player.setLocale(Locale.forLanguageTag(language))
                for (status in listOf("unassigned", "no-assignment")) {
                    every { h.host.workerStatus(idle.id) } returns "workers.status.$status"
                    h.ui.openWorker(h.player, base.id, idle.id)
                    h.body() shouldContain if (language == "ru") "Нет работы" else "No assignment"
                    h.body().contains(if (language == "ru") "Гуляет без работы" else "Walking around · unassigned") shouldBe false
                }
            }
        }
    }

    test("children show their observed activity and hunger without recruitment or work assignment") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val child = base.workers[1].copy(name = "Анна", childMinutes = 0, hunger = 7)
            h.city = base.copy(workers = base.workers.map { if (it.id == child.id) child else it })
            every { h.host.workerStatus(child.id) } returns "workers.status.playing"
            h.open(); h.click("workers")
            h.body() shouldContain "Первые пять жителей приходят сами"
            h.screen.buttons.map { it.id.value } shouldNotContain "recruit"
            h.ui.openWorker(h.player, base.id, child.id)
            h.body() shouldContain "Ребёнок"
            h.body() shouldContain "Играет"
            h.body() shouldContain "\uE604 7/20"
            h.screen.buttons.map { it.id.value } shouldNotContain "assign"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "resident-child-ru", h.screen, listOf(588, 380)) }
            val builder = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            h.ui.openBuilding(h.player, base.id, builder.id); h.click("workers")
            h.screen.buttons.any { plainText(it.label).contains(child.name) } shouldBe false
            verify(exactly = 0) { h.host.recruit(any()); h.host.assign(any(), any(), any()) }
        }
    }

    test("building actions transfer the current requested item and summon its worker once per visit") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val building = base.buildings.first { it.type == BuildingType.BUILDER_HUT }
            h.city = base
            every { h.host.buildingStatus(building.id) } returns "building-status.waiting-ladder"
            every { h.host.buildingRequest(h.player, building.id) } returns CivsBuildingRequest(Material.LADDER, 1, true)
            h.ui.openBuilding(h.player, base.id, building.id)
            h.body() shouldContain "Нужна лестница"
            h.body() shouldContain "Нужно: Лестница × 1"
            plainText(h.screen.buttons.single { it.id.value == "give_request" }.label) shouldBe "Выдать: Лестница"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "building-request-ru", h.screen, listOf(588, 380)) }
            val giveScreen = h.screen
            h.click("give_request", screen = giveScreen); h.click("give_request", screen = giveScreen)
            h.paper.performTicks(2)
            verify(exactly = 1) { h.host.giveBuildingRequest(h.player, building.id, "LADDER") }
            val summonScreen = h.screen
            h.click("summon", screen = summonScreen); h.click("summon", screen = summonScreen)
            h.paper.performTicks(2)
            verify(exactly = 1) { h.host.summonWorker(h.player, building.id) }
            h.screen.id shouldBe "civs.building"
            every { h.host.buildingRequest(h.player, building.id) } returns CivsBuildingRequest(Material.LADDER, 1, false)
            h.ui.openBuilding(h.player, base.id, building.id)
            val unavailable = h.screen.buttons.single { it.id.value == "give_request" }
            unavailable.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(unavailable.tooltip) shouldBe "Нет в инвентаре."
            every { h.host.buildingRequest(h.player, building.id) } returns CivsBuildingRequest(Material.IRON_HOE, 1, true)
            h.ui.openBuilding(h.player, base.id, building.id)
            plainText(h.screen.buttons.single { it.id.value == "give_request" }.label) shouldBe "Выдать мотыгу"
            h.body() shouldContain "Подойдёт любой материал"
            h.body().contains("Железная") shouldBe false
            every { h.host.cityBusy(h.player) } returns true
            h.ui.openBuilding(h.player, base.id, building.id)
            h.screen.buttons.map { it.id.value } shouldNotContain "summon"
            h.screen.buttons.single { it.id.value == "give_request" }.label.color() shouldBe TextColor.color(0xFF6B61)
            h.click("give_request")
            h.screen.id shouldBe "civs.building"
            verify(exactly = 1) { h.host.giveBuildingRequest(any(), any(), any()); h.host.summonWorker(any(), any()) }
        }
    }

    test("building supply keeps the same request button visible when the player lacks the item") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val building = base.buildings.first { it.type == BuildingType.BUILDER_HUT }.copy(type = BuildingType.QUARRY)
            h.city = base.copy(buildings = base.buildings.map { if (it.id == building.id) building else it })
            every { h.host.buildingStatus(building.id) } returns "building-status.waiting-cobblestone"
            var request: CivsBuildingRequest? = CivsBuildingRequest(Material.COBBLESTONE, 1, false)
            every { h.host.buildingRequest(h.player, building.id) } answers { request }
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, id, h.screen, listOf(588, 380)) } }

            h.ui.openBuilding(h.player, base.id, building.id)
            val missing = h.screen.buttons.single { it.id.value == "give_request" }
            plainText(missing.label) shouldBe "Выдать: Булыжник"
            missing.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(missing.tooltip) shouldBe "Нет в инвентаре."
            h.body() shouldContain "Нужно: Булыжник × 1"
            val actionIds = h.screen.buttons.map { it.id }
            val bodyRows = h.screen.body.size
            export("building-request-missing-ru")

            h.click("give_request")
            h.click("give_request")
            verify(exactly = 0) { h.host.giveBuildingRequest(any(), any(), any()) }
            // An unavailable click refreshes the visit instead of consuming every other action.
            h.click("workers")
            h.screen.id shouldBe "civs.building-workers"

            request = request!!.copy(canGive = true)
            h.ui.openBuilding(h.player, base.id, building.id)
            val available = h.screen.buttons.single { it.id.value == "give_request" }
            plainText(available.label) shouldBe plainText(missing.label)
            available.label.color() shouldBe TextColor.color(0x9BD48D)
            plainText(available.tooltip) shouldBe "Передать нужный предмет из своего инвентаря."
            h.screen.buttons.map { it.id } shouldBe actionIds
            h.screen.body.size shouldBe bodyRows
            export("building-request-ready-ru")
            val readyScreen = h.screen
            h.click("give_request", screen = readyScreen)
            h.click("give_request", screen = readyScreen)
            request = null
            h.paper.performTicks(2)
            verify(exactly = 1) { h.host.giveBuildingRequest(h.player, building.id, "COBBLESTONE") }
            h.screen.buttons.map { it.id.value } shouldNotContain "give_request"
        }
    }

    test("new building starts ground selection and reopening the menu cancels that selection") {
        withUi(realHistory = true) { h ->
            h.city = settlement(h.player)
            every { h.host.beginBuildingSelection(any(), any(), any()) } answers {
                h.selection = CivsSelectionView(UUID.randomUUID(), h.player.world.uid, secondArg(),
                    System.currentTimeMillis() + 120_000, false)
                CompletableFuture.completedFuture(Unit)
            }
            every { h.host.cancelSelection(h.player, "menu-reopened") } answers { h.selection = null; Unit }
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_0")
            h.click("prepare"); h.click("style_0"); h.paper.performTicks(2)
            h.selection shouldNotBe null
            h.ui.showingDialog(h.player) shouldBe false
            h.preview shouldBe null
            verify(exactly = 1) { h.host.beginBuildingSelection(h.player, any(), "medieval-oak") }
            verify(exactly = 0) { h.host.selectGround(any(), any(), any()) }
            h.open()
            h.selection shouldBe null
            h.screen.id shouldBe "civs.overview"
            verify(exactly = 2) { h.host.cancelSelection(h.player, "menu-reopened") }
        }
    }

    test("curated decoration categories and variant pages select exact identities with readable dimensions") {
        for (language in listOf("ru", "en")) withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            h.city = settlement(h.player)
            val style = "acacia"
            val choices = List(11) { index -> CivBlueprintChoice(
                "civs/v2/acacia/decorations-fountain-$index/${"a".repeat(20)}/level1", style, BuildingType.DECORATION,
                "machine/source/path/$index", 1, listOf(7 + index, 6, 9 + index), "water") } +
                CivBlueprintChoice("civs/v2/acacia/decorations-garden/${"b".repeat(20)}/level1", style,
                    BuildingType.DECORATION, "machine/source/garden", 1, listOf(8, 4, 8), "gardens")
            every { h.host.styles(BuildingType.DECORATION) } returns listOf(style)
            every { h.host.blueprintChoices(BuildingType.DECORATION, style) } returns choices
            every { h.host.beginBlueprintSelection(h.player, BuildingType.DECORATION, style, choices[8].id) } returns
                CompletableFuture.completedFuture(Unit)
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                exportUiScreen(it, "$id-$language", h.screen, listOf(640, 360))
            } }
            h.open(); h.click("buildings"); h.click("catalog"); h.click("next"); h.click("offer_1"); h.click("prepare"); h.click("style_0")
            h.screen.id shouldBe "civs.blueprint-categories"
            h.screen.buttons.map { it.id.value } shouldBe listOf("category_0", "category_1")
            export("blueprint-categories")
            h.click("category_1")
            h.screen.id shouldBe "civs.blueprint-choices"
            h.screen.buttons.map { it.id.value } shouldBe (0..7).map { "variant_$it" } + "next"
            export("blueprint-choices")
            h.click("next")
            h.screen.buttons.map { it.id.value } shouldBe listOf("variant_0", "variant_1", "variant_2", "previous")
            val variant = h.screen.buttons.first()
            plainText(variant.label) shouldContain "15"
            plainText(variant.label) shouldContain "17"
            plainText(variant.tooltip) shouldContain "6"
            h.screen.buttons.map { plainText(it.label) }.joinToString().contains("machine/") shouldBe false
            export("blueprint-choices-page-2")
            h.click("variant_0"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.beginBlueprintSelection(h.player, BuildingType.DECORATION, style, choices[8].id) }
            verify(exactly = 0) { h.host.beginBuildingSelection(any(), any(), any()) }
        }
    }

    test("blacksmith shows its accepted pickaxe order while the idle production selection remains a shovel") {
        for (language in listOf("ru", "en")) withUi(realHistory = true, decorationFont = "arc:dialog_ui") { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val base = settlement(h.player)
            val forge = base.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH,
                level = 2, production = "IRON_SHOVEL", phase = BuildingPhase.ACTIVE)
            h.city = base.copy(buildings = base.buildings + forge, research = CivResearchState(CivResearchId.entries.toSet()))
            val order = CivCarpentryOrderView(base.workers.first().id, "Александра", "IRON_PICKAXE", 1,
                mapOf("IRON_INGOT" to 3L, "STICK" to 2L), emptyMap(), "IRON_PICKAXE", null, "crafting")
            every { h.host.carpentry(h.player, forge.id) } returns CivCarpentryView(2, false, false,
                listOf(order), CivsCarpentry.views(BuildingType.BLACKSMITH))
            fun export(id: String) { System.getenv("CIVS_UI_PREVIEW_OUT")?.let {
                exportUiScreen(it, "$id-$language", h.screen, listOf(640, 360))
            } }
            val pickaxe = if (language == "ru") "Железная кирка" else "Iron Pickaxe"
            val shovel = if (language == "ru") "Железная лопата" else "Iron Shovel"
            h.ui.openBuilding(h.player, base.id, forge.id)
            h.body() shouldContain pickaxe
            h.body().contains(shovel) shouldBe false
            export("blacksmith-accepted-card")
            h.click("tool_orders")
            h.body() shouldContain pickaxe
            h.body() shouldContain "Александра"
            h.body().contains(shovel) shouldBe false
            export("blacksmith-tool-orders")
            h.click("recipes")
            h.screen.id shouldBe "civs.carpentry-recipes"
            export("blacksmith-tool-recipes")
            h.ui.openBuilding(h.player, base.id, forge.id); h.click("production")
            h.body() shouldContain pickaxe
            h.screen.buttons.map { plainText(it.label) }.joinToString() shouldContain shovel
            export("blacksmith-manual-and-accepted")
            verify(exactly = 0) { h.host.setProduction(any(), any(), any()) }
        }
    }

    test("each building chooses an eligible paginated style and previews that style without changing the settlement default") {
        for (language in listOf("ru", "en")) for (font in listOf("", "arc:dialog_ui")) withUi(realHistory = true, decorationFont = font) { h ->
            h.player.setLocale(Locale.forLanguageTag(language))
            val personal = settlement(h.player)
            h.city = personal
            val styles = listOf("nordic", "medievaloak", "caledonia") + List(7) { "variant-$it" }
            every { h.host.styles(BuildingType.HOUSE) } returns styles
            every { h.host.beginBuildingSelection(h.player, BuildingType.HOUSE, styles.last()) } returns CompletableFuture.completedFuture(Unit)
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_2"); h.click("prepare")
            h.screen.id shouldBe "civs.building-styles"
            h.screen.buttons.map { it.id.value } shouldBe (0..7).map { "style_$it" } + "next"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "building-styles-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) }
            h.click("next")
            h.screen.buttons.map { it.id.value } shouldBe listOf("style_0", "style_1", "previous")
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it,
                "building-styles-page-2-${if (font.isEmpty()) "plain-" else ""}$language", h.screen, listOf(640, 360)) }
            h.click("style_1"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.beginBuildingSelection(h.player, BuildingType.HOUSE, styles.last()) }
            verify(exactly = 0) { h.host.beginBuildingSelection(any(), any()) }
            h.city shouldBe personal
            h.preview = preview().copy(type = BuildingType.HOUSE, style = styles.last())
            h.open()
            h.body() shouldContain styles.last()
            h.preview = null
            every { h.host.styles(BuildingType.HOUSE) } returns emptyList()
            h.open(); h.click("buildings"); h.click("catalog"); h.click("offer_2"); h.click("prepare")
            h.screen.buttons shouldBe emptyList()
            h.body() shouldContain if (language == "ru") "нет доступных вариантов" else "no available variants"
        }
    }

    test("testing settings show normal defaults and only permitted toggles in both locales") {
        withUi { h ->
            h.city = settlement(h.player)
            h.open(); h.click("management")
            h.screen.buttons.map { it.id.value } shouldNotContain "testing_settings"
            var allowed = CivSettlementSetting.entries.toSet()
            every { h.host.canChangeSettlementSetting(h.player, any()) } answers { secondArg<CivSettlementSetting>() in allowed }
            for (locale in listOf("ru", "en")) {
                h.player.setLocale(Locale.forLanguageTag(locale))
                h.open(); h.click("management"); h.click("testing_settings")
                h.screen.id shouldBe "civs.testing-settings"
                h.screen.buttons.map { it.id.value } shouldBe listOf("chunk_loading", "immortal_residents", "auto_construction_materials")
                h.screen.buttons.map { plainText(it.label).first() } shouldBe listOf('✔', '○', '○')
                h.screen.buttons.map { it.label.color() } shouldBe
                    listOf(TextColor.color(0x9BD48D), TextColor.color(0xFFFFFF), TextColor.color(0xFFFFFF))
                h.screen.buttons.forEach {
                    plainText(it.tooltip).startsWith('\n') shouldBe true
                    plainText(it.tooltip).endsWith('\n') shouldBe true
                    plainText(it.tooltip).contains("\n\n") shouldBe true
                }
                System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "testing-settings-defaults-$locale", h.screen, listOf(588, 380)) }
            }
            h.player.setLocale(Locale.forLanguageTag("ru"))
            h.city = h.city!!.copy(settings = CivSettlementSettings(immortalResidents = true, autoConstructionMaterials = true))
            h.open(); h.click("management"); h.click("testing_settings")
            h.screen.buttons.all { plainText(it.label).startsWith("✔ ") } shouldBe true
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "testing-settings-enabled-ru", h.screen, listOf(588, 380)) }
            allowed = allowed - CivSettlementSetting.CHUNK_LOADING
            h.open(); h.click("management"); h.click("testing_settings")
            h.screen.buttons.map { it.id.value } shouldBe listOf("immortal_residents", "auto_construction_materials")
            h.body() shouldContain "Загрузка чанков"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "testing-settings-restricted-ru", h.screen, listOf(588, 380)) }
            verify(exactly = 0) { h.host.setSettlementSetting(any(), any(), any()) }
        }
    }

    test("testing toggles retain the page while saving and show only committed state without duplicate history") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru"))
            h.city = settlement(h.player)
            every { h.host.canChangeSettlementSetting(h.player, any()) } returns true
            val pending = CompletableFuture<Unit>()
            every { h.host.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true) } returns pending
            h.open(); h.click("management"); h.click("testing_settings")
            val before = h.screen
            val count = h.screens.size
            h.click("immortal_residents"); h.click("immortal_residents", screen = before)
            h.screens.size shouldBe count
            plainText(h.screen.buttons.single { it.id.value == "immortal_residents" }.label) shouldBe "○ Бессмертие жителей"
            verify(exactly = 1) { h.host.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true) }
            h.city = h.city!!.copy(settings = CivSettlementSettings(immortalResidents = true))
            pending.complete(Unit); h.paper.performTicks(2)
            h.screens.size shouldBe count + 1
            plainText(h.screen.buttons.single { it.id.value == "immortal_residents" }.label) shouldBe "✔ Бессмертие жителей"
            for ((setting, enabled) in listOf(CivSettlementSetting.CHUNK_LOADING to false, CivSettlementSetting.AUTO_CONSTRUCTION_MATERIALS to true)) {
                every { h.host.setSettlementSetting(h.player, setting, enabled) } answers {
                    h.city = h.city!!.copy(settings = h.city!!.settings!!.with(setting, enabled))
                    CompletableFuture.completedFuture(Unit)
                }
                h.click(setting.name.lowercase(Locale.ROOT)); h.paper.performTicks(2)
                plainText(h.screen.buttons.single { it.id.value == setting.name.lowercase(Locale.ROOT) }.label).first() shouldBe
                    if (enabled) '✔' else '○'
                verify(exactly = 1) { h.host.setSettlementSetting(h.player, setting, enabled) }
            }
            h.click("history")
            h.screen.id shouldBe "civs.management"
            h.click("history")
            h.screen.id shouldBe "civs.overview"
            verify(exactly = 0) { h.player.closeDialog() }
        }
    }

    test("testing toggle rejection retains its saved state and revoked permission blocks a stale action") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru"))
            h.city = settlement(h.player)
            var allowed = CivSettlementSetting.entries.toSet()
            every { h.host.canChangeSettlementSetting(h.player, any()) } answers { secondArg<CivSettlementSetting>() in allowed }
            every { h.host.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true) } returns
                CompletableFuture.failedFuture(CivsRejection("city-busy"))
            h.open(); h.click("management"); h.click("testing_settings")
            h.click("immortal_residents"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.testing-settings"
            plainText(h.screen.buttons.single { it.id.value == "immortal_residents" }.label) shouldBe "○ Бессмертие жителей"
            h.body() shouldContain plainText(CivsText.fromYaml(civsLocaleSource()).reason(h.player, "city-busy"))
            val before = h.screen
            allowed = allowed - CivSettlementSetting.AUTO_CONSTRUCTION_MATERIALS
            h.click("auto_construction_materials", screen = before)
            h.screen.buttons.map { it.id.value } shouldNotContain "auto_construction_materials"
            h.body() shouldContain "У вас нет доступа к этому действию."
            verify(exactly = 0) { h.host.setSettlementSetting(h.player, CivSettlementSetting.AUTO_CONSTRUCTION_MATERIALS, any()) }
            allowed = emptySet()
            h.click("chunk_loading")
            h.screen.id shouldBe "civs.management"
            h.screen.buttons.map { it.id.value } shouldNotContain "testing_settings"
        }
    }

    test("settlement management keeps existing submenus and returns through real Core history") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.city = settlement(h.player)
            h.open()
            h.screen.buttons.map { it.id.value } shouldBe listOf("management", "buildings", "workers", "research", "industry", "upgrade", "hall_inventory", "guide")
            plainText(h.screen.buttons.first().label) shouldBe "Управление поселением ›"
            h.click("management")
            h.screen.id shouldBe "civs.management"
            plainText(h.screen.title) shouldBe "Управление поселением"
            h.screen.buttons.map { it.id.value } shouldBe listOf("stock", "upgrades", "members", "delete_city")
            h.screen.buttons.single { it.id.value == "delete_city" }.label.color() shouldBe TextColor.color(0xFF6B61)
            plainText(h.screen.buttons.last().label) shouldBe "Удалить поселение"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "management-colors-ru", h.screen, listOf(588, 380)) }
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

    test("town overview opens its exact hall barrel in either storage mode and hides unavailable access") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
            for (mode in CivStorageMode.entries) {
                h.city = base.copy(inventory = base.inventory!!.copy(mode = mode))
                h.open()
                h.screen.id shouldBe "civs.overview"
                val action = h.screen.buttons.single { it.id.value == "hall_inventory" }
                plainText(action.label) shouldBe "Инвентарь ратуши ›"
                val before = h.screen
                h.click("hall_inventory"); h.click("hall_inventory", screen = before)
                h.ui.showingDialog(h.player) shouldBe false
            }
            verify(exactly = 2) { h.host.openBuildingInventory(h.player, hall.id) }
            every { h.host.cityBusy(h.player) } returns true
            h.open()
            h.screen.buttons.map { it.id.value } shouldNotContain "hall_inventory"
            every { h.host.cityBusy(h.player) } returns false
            h.city = base.copy(buildings = base.buildings.map { if (it.id == hall.id) it.copy(control = null) else it })
            h.open()
            h.screen.buttons.map { it.id.value } shouldNotContain "hall_inventory"
        }
    }

    test("town hall overview starts its upgrade in one click and keeps transaction progress on the same screen") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
            h.city = base
            val pending = CompletableFuture<Unit>()
            every { h.host.upgradeBuilding(h.player, hall.id) } returns pending
            h.open()
            h.screen.id shouldBe "civs.overview"
            h.body() shouldContain "Новый уровень"
            h.body() shouldContain "Ратуша · ур. 3"
            val action = h.screen.buttons.single { it.id.value == "upgrade" }
            plainText(action.label) shouldBe "Улучшить ратушу"
            action.label.color() shouldBe TextColor.color(0x9BD48D)
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "town-hall-direct-upgrade-ru", h.screen, listOf(588, 380)) }
            val before = h.screen
            h.click("upgrade"); h.click("upgrade", screen = before)
            h.screen.id shouldBe "civs.overview"
            h.body() shouldContain "Начинаем улучшение"
            verify(exactly = 1) { h.host.upgradeBuilding(h.player, hall.id) }
            h.screens.any { it.id == "civs.preview" || it.id == "civs.upgrade" } shouldBe false
            pending.complete(Unit); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.overview"
            verify(exactly = 0) { h.host.confirmPreview(any()) }
        }
    }

    test("overview omits stock quantities and members use saved names with an owner profile instead of an owner table") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val member = UUID(0, 731)
            h.city = base.copy(members = setOf(member),
                memberNames = mapOf(h.player.uniqueId.toString() to "MayorSaved", member.toString() to "OfflineResident"),
                inventory = base.inventory!!.copy(mode = CivStorageMode.VIRTUAL, materials = mapOf("BREAD" to 1497L)))
            h.open()
            h.body().contains("Хлеб") shouldBe false
            h.body().contains("1497") shouldBe false
            h.body() shouldContain "Уровень 2 · Радиус: 64 блоков"
            h.body() shouldContain "Жители: 4 / 8 · Спальные места: 8"
            h.body() shouldContain "Постройки: 4 / 16 · Город спит"
            h.body() shouldContain "Стройка: Нет активной стройки"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "overview-clean-ru", h.screen, listOf(588, 380)) }
            h.player.setLocale(Locale.US); h.open()
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "overview-clean-en", h.screen, listOf(588, 380)) }
            h.player.setLocale(Locale.forLanguageTag("ru-RU")); h.open()
            h.click("management"); h.click("stock"); h.click("materials")
            h.body() shouldContain "Хлеб"
            h.open(); h.click("management"); h.click("members")
            h.body().contains("MayorSaved") shouldBe false
            plainText(h.screen.buttons.single { it.id.value == "owner" }.label) shouldBe "MayorSaved ›"
            plainText(h.screen.buttons.single { it.id.value == "member_0" }.label) shouldBe "OfflineResident ›"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "members-saved-names-ru", h.screen, listOf(588, 380)) }
            h.click("owner")
            h.body() shouldContain "MayorSaved"
            h.body() shouldContain "Владелец города"
            h.body().contains("Удаление") shouldBe false
            h.screen.buttons.map { it.id.value } shouldNotContain "remove"
            h.click("history"); h.click("member_0")
            h.body() shouldContain "OfflineResident"
            h.screen.buttons.map { it.id.value }.contains("remove") shouldBe true
            verify(exactly = 0) { h.host.removeMember(any(), any()) }
        }
    }

    test("building overview shows levels honest construction states and worker counts with nearby paged actions") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val types = listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.STONEMASON,
                BuildingType.FORESTER, BuildingType.HOUSE, BuildingType.WAREHOUSE, BuildingType.BLACKSMITH, BuildingType.FARM) +
                List(8) { BuildingType.HOUSE }
            val buildings = types.mapIndexed { index, type ->
                base.buildings.first().copy(id = UUID(0, index.toLong()), type = type)
            }
            val target = buildings[3]
            h.city = base.copy(buildings = buildings, constructionQueue = listOf(buildings[4].id),
                workers = base.workers.mapIndexed { index, worker -> worker.copy(assignment = if (index < 2) buildings[1].id else null) },
                construction = CivConstruction(UUID.randomUUID(), target.id, 3, 500, 40, ConstructionPhase.CONSTRUCTING, Stock()))
            h.open(); h.click("buildings")
            val actions = h.screen.buttons.filter { it.id.value.startsWith("building_") }
            actions.size shouldBe 8
            h.screen.columns shouldBe 2
            actions.map { it.label.color() }.distinct().size shouldBe 8
            h.screen.buttons.map { it.id.value }.contains("next") shouldBe true
            h.screen.buttons.map { it.id.value } shouldNotContain "queue"
            val rows = materialTableCells(h.screen)
            rows.first() shouldBe listOf("Постройка", "Ур.", "Состояние", "Работники")
            rows.single { it.first() == "Дом строителя" } shouldBe listOf("Дом строителя", "2", "Готово", "2 / 2")
            rows.single { it.first() == "Лесорубка" }[2] shouldBe "Улучшается → 3"
            rows.single { it.first() == "Дом" }[2] shouldBe "В очереди · №1"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "buildings-upgrading-ru", h.screen, listOf(588, 380)) }
            h.click("building_2")
            h.screen.id shouldBe "civs.building"
            h.body() shouldContain "Каменотёсная"
            h.open(); h.click("buildings"); h.click("next")
            h.screen.buttons.count { it.id.value.startsWith("building_") } shouldBe 8
            h.screen.buttons.map { it.id.value } shouldNotContain "next"
            h.city = h.city!!.copy(buildings = buildings.map { if (it.id == target.id) it.copy(level = 1, phase = BuildingPhase.CONSTRUCTING) else it },
                construction = h.city!!.construction!!.copy(targetLevel = 1))
            h.open(); h.click("buildings")
            materialTableCells(h.screen).single { it.first() == "Лесорубка" }.take(3) shouldBe listOf("Лесорубка", "1", "Строится")
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "buildings-constructing-ru", h.screen, listOf(588, 380)) }
            h.player.setLocale(Locale.US)
            h.open(); h.click("buildings")
            materialTableCells(h.screen).any { it.first() == "Stonemason" } shouldBe true
            materialTableCells(h.screen).any { it.first() == "workshop" } shouldBe true
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "buildings-constructing-en", h.screen, listOf(588, 380)) }
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

    test("the plan board is selectable and its ordinary detail offers no job production or upgrade") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val board = base.buildings.last().copy(id = UUID(83, 17), type = BuildingType.PLAN_BOARD, level = 1,
                control = CivPosition(6, 65, 2))
            h.city = base.copy(buildings = base.buildings + board)
            h.open(); h.click("buildings"); h.click("catalog")
            repeat(3) {
                if (h.screen.buttons.none { plainText(it.label).contains("План поселения") }) h.click("next")
            }
            val offer = h.screen.buttons.single { plainText(it.label).contains("План поселения") }
            h.click(offer.id.value)
            h.screen.id shouldBe "civs.offer"
            h.body() shouldContain "Карта поселения с домами, дорогами, воротами и стенами"
            h.body() shouldContain "показывает будущую застройку"
            h.screen.buttons.any { it.id.value == "prepare" } shouldBe true
            h.ui.openBuilding(h.player, base.id, board.id)
            h.screen.id shouldBe "civs.building"
            for (action in listOf("workers", "production", "upgrade", "storage"))
                h.screen.buttons.map { it.id.value } shouldNotContain action
            h.screen.buttons.any { it.id.value == "delete_building" } shouldBe true
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
            val close = h.screen.exitButton!!
            plainText(close.label) shouldBe plainText(CivsText.fromYaml(civsLocaleSource()).render(h.player, "common.close"))
            h.click(close.id.value)
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
            h.body() shouldContain "Settlement changes are temporarily unavailable"
            every { h.host.cityBusy(h.player) } returns false
            h.open(); h.click("management"); h.click("delete_city")
            every { h.host.deleteCity(any(), any(), any()) } returns CompletableFuture.failedFuture(CivsRejection("city-busy"))
            h.click("delete", mapOf("delete_city_name" to city.name)); h.paper.performTicks(2)
            h.screen.inputs.single().initial shouldBe city.name
            h.body() shouldContain "Settlement changes are temporarily unavailable"
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

    test("building details show the resolved work reason rather than the saved building phase") {
        withUi { h ->
            val base = settlement(h.player)
            val building = base.buildings.last().copy(control = CivPosition(16, 66, 1))
            h.city = base.copy(buildings = base.buildings.map { if (it.id == building.id) building else it })
            for ((status, label) in listOf("no-workers" to "No workers assigned", "route-unreachable" to "Worker cannot reach the destination",
                "waiting-materials" to "Waiting for materials", "working" to "Working", "no-construction" to "No active construction",
                "work-route-too-long" to "Storage is too far away", "control-missing" to "Control barrel is missing",
                "lands-denied" to "No permission for this plot", "world-changed" to "The plot has changed",
                "protected-terrain" to "A protected block cannot be replaced", "building-overlap" to "Overlaps another building",
                "outside-city" to "Outside town borders", "protected-area" to "The plot is protected")) {
                every { h.host.buildingStatus(building.id) } returns "building-status.$status"
                h.ui.openBuilding(h.player, base.id, building.id)
                // Core wraps a measured table cell into framed rows; keep checking the whole label.
                h.body().replace(Regex("\\p{Co}"), "").replace(Regex("\\s+"), " ") shouldContain label
                h.body().contains("building-status.") shouldBe false
            }
            every { h.host.buildingStatus(building.id) } returns "building-status.future-internal-key"
            h.ui.openBuilding(h.player, base.id, building.id)
            h.body() shouldContain "Status is updating"
            h.body().contains("future-internal-key") shouldBe false

            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val worker = base.workers.first()
            every { h.host.workerStatus(worker.id) } returns "workers.status.route-unreachable"
            h.ui.openWorker(h.player, base.id, worker.id)
            h.body() shouldContain "Нет доступного пути"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "worker-route-ru", h.screen, listOf(588, 380)) }

            h.city = h.city!!.copy(construction = CivConstruction(UUID.randomUUID(), building.id, 1, 475, 40,
                ConstructionPhase.WAITING_MATERIALS, Stock(), plannedBuilding = building.copy(phase = BuildingPhase.CONSTRUCTING),
                materials = builderMaterialEstimate(), payment = CivConstructionPayment()))
            every { h.host.buildingStatus(building.id) } returns "building-status.waiting-materials"
            h.ui.openBuilding(h.player, base.id, building.id)
            h.body() shouldContain "Ждёт материалы"
            h.body() shouldContain "40 / 475"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "building-waiting-ru", h.screen, listOf(588, 380)) }
        }
    }

    test("site starts join the existing construction queue and queued materials cannot enqueue twice") {
        withUi { h ->
            val base = settlement(h.player)
            val active = base.buildings.last()
            val first = active.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, phase = BuildingPhase.SITE)
            val second = active.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.SITE)
            h.city = base.copy(buildings = base.buildings + first + second,
                construction = CivConstruction(UUID.randomUUID(), active.id, 1, 475, 40, ConstructionPhase.CONSTRUCTING,
                    Stock(), materials = mapOf("COBBLESTONE" to 19L), payment = CivConstructionPayment()),
                constructionQueue = listOf(first.id))
            every { h.host.buildingStatus(any()) } answers {
                "building-status.${when (firstArg<UUID>()) {
                    active.id -> "working"
                    in h.city!!.constructionQueue.orEmpty() -> "queued"
                    else -> "site"
                }}"
            }
            every { h.host.buildingMaterials(h.player, second.id) } returns CompletableFuture.completedFuture(mapOf("COBBLESTONE" to 19L))
            every { h.host.availableMaterials(h.player) } returns emptyMap()
            every { h.host.startBuilding(h.player, second.id) } answers {
                h.city = h.city!!.copy(constructionQueue = h.city!!.constructionQueue.orEmpty() + second.id)
                CompletableFuture.completedFuture(Unit)
            }
            h.ui.openBuilding(h.player, base.id, second.id); h.click("materials"); h.paper.performTicks(2)
            plainText(h.screen.buttons.single { it.id.value == "start" }.label) shouldBe "Queue"
            val form = h.screen
            h.click("start"); h.click("start", screen = form); h.paper.performTicks(2)
            h.body() shouldContain "Queued · No. 2"
            verify(exactly = 1) { h.host.startBuilding(h.player, second.id) }
            h.click("materials"); h.paper.performTicks(2)
            h.body() shouldContain "Queued · No. 2"
            materialTableCells(h.screen).last() shouldBe listOf("Cobblestone", "19")
            h.screen.buttons.map { it.id.value } shouldBe listOf("supply")

            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.ui.openBuilding(h.player, base.id, second.id); h.click("queue")
            h.screen.id shouldBe "civs.queue"
            h.body() shouldContain "40/475"
            plainText(h.screen.buttons.single { it.id.value == "queue_0" }.label) shouldBe "№1 · Склад · ур. 1 ›"
            plainText(h.screen.buttons.single { it.id.value == "queue_1" }.label) shouldBe "№2 · Дом · ур. 1 ›"
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "construction-queue-ru", h.screen, listOf(588, 380)) }
            h.click("queue_1")
            h.screen.id shouldBe "civs.building"
            h.body() shouldContain "В очереди · №2"
            h.screen.buttons.any { it.id.value == "refresh" } shouldBe false

            val third = second.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY)
            h.city = h.city!!.copy(buildings = h.city!!.buildings + third, construction = null)
            every { h.host.buildingMaterials(h.player, third.id) } returns CompletableFuture.completedFuture(emptyMap())
            h.ui.openBuilding(h.player, base.id, third.id); h.click("materials"); h.paper.performTicks(2)
            plainText(h.screen.buttons.single { it.id.value == "start" }.label) shouldBe "В очередь"
        }
    }

    test("active buildings can queue upgrades and retain their queue position and target level") {
        withUi { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val target = base.buildings.last()
            val active = base.buildings[1]
            h.city = base.copy(construction = CivConstruction(UUID.randomUUID(), active.id, 2, 500, 0,
                ConstructionPhase.CONSTRUCTING, Stock()))
            every { h.host.upgradeBuilding(h.player, target.id) } returns CompletableFuture.completedFuture(Unit)
            h.ui.openBuilding(h.player, base.id, target.id); h.click("upgrade"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.building"
            verify(exactly = 1) { h.host.upgradeBuilding(h.player, target.id) }
            h.city = h.city!!.copy(constructionQueue = listOf(target.id))
            h.ui.openBuilding(h.player, base.id, target.id)
            h.body() shouldContain "В очереди · №1"
            h.screen.buttons.map { it.id.value } shouldNotContain "upgrade"
            h.click("materials"); h.paper.performTicks(2)
            h.screen.id shouldBe "civs.building-materials"
            h.body() shouldContain "Лесорубка · ур. 2"
            h.body() shouldContain "В очереди · №1"
            h.screen.buttons.map { it.id.value } shouldNotContain "start"
            h.ui.openBuilding(h.player, base.id, target.id); h.click("queue")
            plainText(h.screen.buttons.single { it.id.value == "queue_0" }.label) shouldContain "Лесорубка · ур. 2"
        }
    }

    test("deleting an ordinary building requires confirmation and binds the exact city and building") {
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            val base = settlement(h.player)
            val hall = base.buildings.first()
            val building = base.buildings.last()
            h.city = base.copy(inventory = CivInventory(CivStorageMode.PHYSICAL, buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 12L))),
                construction = CivConstruction(UUID.randomUUID(), building.id, 2, 475, 40, ConstructionPhase.CONSTRUCTING,
                    Stock(), payment = CivConstructionPayment()))
            h.ui.openBuilding(h.player, base.id, hall.id)
            h.screen.buttons.any { it.id.value == "delete_building" } shouldBe false
            h.ui.openBuilding(h.player, base.id, building.id); h.click("delete_building")
            h.screen.id shouldBe "civs.delete-building"
            h.body() shouldContain "Уже поставленные блоки"
            h.body() shouldContain "содержимое выпадет на землю"
            h.body() shouldContain "Уже потраченные материалы не возвращаются"
            h.body() shouldContain "перейдут в общий резерв"
            h.screen.buttons.single().label.color() shouldBe TextColor.color(0xFF6B61)
            h.screen.inputs shouldBe emptyList()
            verify(exactly = 0) { h.host.deleteBuilding(any(), any(), any()) }
            System.getenv("CIVS_UI_PREVIEW_OUT")?.let { exportUiScreen(it, "delete-building-ru", h.screen, listOf(588, 380)) }
            val pending = CompletableFuture<Unit>()
            every { h.host.deleteBuilding(h.player, base.id, building.id) } returns pending
            val confirmation = h.screen
            h.click("delete"); h.click("delete", screen = confirmation)
            h.city = h.city!!.copy(buildings = base.buildings.filterNot { it.id == building.id }, construction = null)
            pending.complete(Unit); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.deleteBuilding(h.player, base.id, building.id) }
            h.screen.id shouldBe "civs.buildings"
            h.screen.buttons.none { plainText(it.label).contains("Лесорубка") } shouldBe true
            val close = h.screen.exitButton!!
            plainText(close.label) shouldBe plainText(CivsText.fromYaml(civsLocaleSource()).render(h.player, "common.close"))
            h.click(close.id.value)
            h.ui.showingDialog(h.player) shouldBe false
            h.screen.id shouldBe "civs.buildings"
        }
    }

    test("building deletion hides while busy and stale confirmation keeps its original city id") {
        withUi { h ->
            val base = settlement(h.player)
            val building = base.buildings.last()
            h.city = base
            every { h.host.cityBusy(h.player) } returns true
            h.ui.openBuilding(h.player, base.id, building.id)
            h.screen.buttons.any { it.id.value == "delete_building" } shouldBe false
            every { h.host.cityBusy(h.player) } returns false
            h.ui.openBuilding(h.player, base.id, building.id); h.click("delete_building")
            val replacement = base.copy(id = UUID.randomUUID())
            h.city = replacement
            every { h.host.deleteBuilding(h.player, base.id, building.id) } returns CompletableFuture.failedFuture(CivsRejection("stale-city"))
            h.click("delete"); h.paper.performTicks(2)
            verify(exactly = 1) { h.host.deleteBuilding(h.player, base.id, building.id) }
            h.city shouldBe replacement
            h.body() shouldContain "The town has changed"
            h.screen.buttons.any { it.id.value == "delete" } shouldBe false
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
        withUi(realHistory = true) { h ->
            h.player.setLocale(Locale.forLanguageTag("ru-RU"))
            h.city = settlement(h.player)
            every { h.player.hasPermission("arccivs.admin") } returns true
            fun export(id: String, screenId: String) {
                h.screen.id shouldBe "civs.$screenId"
                exportUiScreen(directory, id, h.screen, listOf(588, 380))
            }

            h.open(); h.click("management"); h.click("admin")
            export("admin-overview-ru", "admin")
            h.click("city_0")
            export("admin-city-ru", "admin-city")
            h.click("history"); h.click("reload")
            export("admin-reload-ru", "admin-reload")

            h.open(); h.click("management"); h.click("members"); h.click("add")
            export("member-add-ru", "member-add")
            h.open(); h.click("management"); h.click("upgrades"); h.click("upgrade_0")
            export("upgrade-detail-ru", "upgrade")
            h.ui.openCorpse(h.player, h.city!!.id, UUID.randomUUID())
            export("corpse-expired-ru", "corpse")

            verify(exactly = 0) {
                h.host.reload(any())
                h.host.addMember(any(), any())
                h.host.upgradeBuilding(any(), any())
                h.host.transferCorpseItem(any(), any(), any(), any())
            }
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

private fun withUi(realHistory: Boolean = false, decorationFont: String = "", action: (UiHarness) -> Unit) = MockBukkitTestRuntime.open().use { paper ->
    val harness = UiHarness(paper, realHistory, decorationFont)
    try { action(harness) } finally { harness.ui.close(); harness.tasks.close() }
}

private class UiHarness(val paper: MockBukkitTestRuntime, private val realHistory: Boolean, decorationFont: String = "") {
    val plugin = paper.createSimplePlugin("CivsUiTest").also { it.config.set("presentation.font", decorationFont) }
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

    private fun record(shown: PaperDialogScreen) {
        screens += shown
        System.getenv("CIVS_UI_INVENTORY_OUT")?.let { directory ->
            val id = "inventory-${shown.id.removePrefix("civs.")}-${player.locale().language}"
            if (!Files.exists(Path.of(directory).resolve("$id.json"))) exportUiScreen(directory, id, shown, listOf(588, 380))
        }
    }

    init {
        every { player.closeDialog() } just Runs
        every { host.ready() } returns true
        every { host.unavailableReason() } returns null
        every { host.cityBusy(any()) } returns false
        every { host.canChangeSettlementSetting(any(), any()) } returns false
        every { host.availableMaterials(any()) } answers {
            city?.inventory?.materials.orEmpty().toMutableMap().also { total ->
                city?.inventory?.buildingMaterials.orEmpty().values.forEach { stock ->
                    stock.forEach { (material, count) -> total[material] = (total[material] ?: 0) + count }
                }
            }
        }
        // Existing isolated UI fixtures use this map as their visible store. Locality is verified separately below.
        every { host.localMaterials(any(), any()) } answers { host.availableMaterials(firstArg()) }
        every { host.supplyRequests(any()) } returns emptyList()
        every { host.constructionAvailable(any(), any()) } answers { host.localMaterials(firstArg(), secondArg()) }
        every { host.buildingMaterials(any(), any()) } answers {
            CompletableFuture.completedFuture(city?.construction?.takeIf { it.buildingId == secondArg<UUID>() }?.materials.orEmpty())
        }
        every { host.buildingControl(any(), any()) } answers {
            val building = requireNotNull(city?.building(secondArg()))
            CompletableFuture.completedFuture(building.anchor.copy(y = building.anchor.y + 2))
        }
        every { host.relocateControl(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.startBuilding(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.supplyConstruction(any(), any()) } returns CompletableFuture.completedFuture(CivsSupplyResult(0L, 0L, false))
        every { host.supplyConstruction(any(), any(), any()) } returns CompletableFuture.completedFuture(CivsSupplyResult(0L, 0L, false))
        every { host.supplyBuilderMaterials(any(), any()) } returns CompletableFuture.completedFuture(CivsSupplyResult(0L, 0L, false))
        every { host.grantConstructionMaterials(any(), any()) } returns CompletableFuture.completedFuture(CivsSupplyResult(0L, 0L, false))
        every { host.grantConstructionMaterials(any(), any(), any()) } returns CompletableFuture.completedFuture(CivsSupplyResult(0L, 0L, false))
        every { host.grantBuilderMaterials(any(), any()) } returns CompletableFuture.completedFuture(CivsSupplyResult(0L, 0L, false))
        every { host.deleteCity(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.openBuildingInventory(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.materializeStorage(any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.depositMaterials(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.withdrawMaterial(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.refreshCity(any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.remoteCity(any()) } returns null
        every { host.foundingAllowed() } returns true
        every { host.styles() } returns listOf("medieval-oak")
        every { host.styles(any<BuildingType>()) } answers { host.styles() }
        // These legacy fixture schematics have no v2 catalog entry, matching the real Host fallback.
        every { host.maximumBuildingLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { host.city(any()) } answers { city }
        every { host.settlements() } answers { listOfNotNull(city) }
        every { host.nextAutonomyGoal(any()) } returns null
        every { host.preview(any()) } answers { preview }
        every { host.selection(any()) } answers { selection }
        every { host.workerStatus(any()) } returns "workers.status.sleeping"
        every { host.toolRequest(any(), any()) } returns null
        every { host.workerRequest(any(), any()) } returns null
        every { host.giveWorkerRequest(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.residentItems(any(), any()) } answers {
            val workerId = secondArg<UUID>()
            city?.workers?.firstOrNull { it.id == workerId }?.let { worker -> CivsEquipment.slots(worker).map { it?.stack() } }
                ?: List(33) { null }
        }
        every { host.transferResidentItem(any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.buildingStatus(any()) } returns "building-status.active"
        every { host.buildingRequest(any(), any()) } returns null
        every { host.giveBuildingRequest(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.summonWorker(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.memberName(any(), any()) } answers {
            val id = secondArg<UUID>()
            firstArg<Settlement>().memberNames?.get(id.toString()) ?:
                if (id == player.uniqueId) player.name else id.toString().take(8)
        }
        every { host.deleteBuilding(any(), any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { host.confirmPreview(any()) } answers {
            city = settlement(player)
            preview = null
            CompletableFuture.completedFuture(Unit)
        }
        if (realHistory) {
            // The same presenter seam used by ArcRanks tests retains real Core sessions and history.
            val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, shown, registered ->
                record(shown)
                registration = registered
            }
            val constructor = PaperDialogRuntime::class.java.declaredConstructors
                .first { it.parameterCount == 2 }.apply { isAccessible = true }
            runtime = constructor.newInstance(plugin, presenter) as PaperDialogRuntime
        } else {
            runtime = mockk(relaxed = true)
            every { runtime.open(any(), any(), any(), any(), any()) } answers {
                record(secondArg<PaperDialogScreen>())
                dismissAction = arg(3)
                Unit
            }
            every { runtime.close(any()) } answers { dismissAction(); Unit }
            every { runtime.beginFlow(any()) } answers { dismissAction(); Unit }
        }
        ui = CivsUi(plugin, host, tasks, runtime, CivsText.fromYaml(civsLocaleSource()))
    }

    fun open() { ui.open(player); paper.performTicks(2) }
    fun nativeClick(raw: Int, click: ClickType = ClickType.LEFT): InventoryClickEvent = InventoryClickEvent(
        player.openInventory, InventoryType.SlotType.CONTAINER, raw, click, InventoryAction.PICKUP_ALL,
    ).also { plugin.server.pluginManager.callEvent(it) }
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

private fun materialTableCells(screen: PaperDialogScreen): List<List<String>> {
    val base = listOf(0xE540, 0xE550, 0xE560, 0xE570, 0xE580, 0xE590)
        .single { point -> screen.body.any { plainText(it.text).contains(point.toChar()) } }
    return screen.body.single { plainText(it.text).contains(base.toChar()) }.let { plainText(it.text) }.lineSequence()
        .filter { it.contains((base + 4).toChar()) }.map { line -> line.split((base + 5).toChar()).map { cell -> buildString {
            cell.codePoints().filter { it !in base..base + 14 && it !in 0xF0F01..0xF0F11 }.forEach(::appendCodePoint)
        } } }.toList()
}

/** Read the two visible columns from either the authored panel or the native fallback. */
private fun logisticsTableCells(screen: PaperDialogScreen): List<List<String>> {
    fun panel(component: Component): Component? {
        if (component.children().firstOrNull()?.font()?.asString() == "arc:dialog_panel_navy") return component
        return component.children().firstNotNullOfOrNull(::panel)
    }
    val framed = screen.body.firstNotNullOfOrNull { panel(it.text) }
    if (framed != null) return framed.children().drop(1).filter { it.children().size in listOf(5, 9) }.map { line ->
        listOf(line.children()[1], line.children()[if (line.children().size == 9) 5 else 3]).map {
            plainText(it).replace(Regex("\\p{Co}"), "").trim()
        }
    }
    return screen.body.flatMap { plainText(it.text).lineSequence().map { it.split(" · ") }
        .filter { it.size == 2 }.toList() }
}

/** Reassemble wrapped cell lines so a dense-board assertion checks every full name and deficit. */
private fun logisticsMaterialRows(screen: PaperDialogScreen): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    for (line in logisticsTableCells(screen).dropWhile { it[1].toLongOrNull() == null }) {
        if (line[1].isNotBlank()) rows += line.toMutableList()
        else line.forEachIndexed { column, value ->
            if (value.isNotBlank()) rows.last()[column] = listOf(rows.last()[column], value).filter(String::isNotBlank).joinToString(" ")
        }
    }
    return rows
}

private fun builderMaterialEstimate() = mapOf("CHISELED_BOOKSHELF" to 2L, "COBBLESTONE" to 4L,
    "CRAFTING_TABLE" to 1L, "DIRT" to 161L, "GRAVEL" to 47L, "SPRUCE_DOOR" to 2L, "SPRUCE_FENCE" to 83L,
    "SPRUCE_FENCE_GATE" to 1L, "SPRUCE_LOG" to 142L, "SPRUCE_PLANKS" to 182L, "SPRUCE_SLAB" to 4L,
    "SPRUCE_STAIRS" to 233L, "TORCH" to 34L)

private fun autonomousSettlement(player: Player, name: String): Settlement {
    val base = settlement(player)
    val buildings = CivsAutonomy.profile.map { type ->
        (base.buildings.firstOrNull { it.type == type } ?: base.buildings.first()).copy(id = UUID.randomUUID(), type = type, level = 1)
    }
    return base.copy(name = name, inventory = null, autonomy = CivAutonomy(), stock = Stock(48, 32, 16), buildings = buildings,
        workers = base.workers.mapIndexed { index, worker -> worker.copy(name = "Житель ${index + 1}", assignment = buildings[index].id) })
}

private fun settlement(player: Player): Settlement {
    // Aptitude uses UUID.hashCode(); pin the trait while keeping each fixture's IDs unique.
    val workerSeed = UUID.randomUUID().mostSignificantBits
    fun workerId(index: Long) = UUID(workerSeed, workerSeed xor index)
    fun building(type: BuildingType, x: Int, level: Int = 1) = CivBuilding(UUID.randomUUID(), type, level,
        CivPosition(x, 64, 0), 0, "medieval-oak/${type.name.lowercase()}",
        CivBox(CivPosition(x, 64, 0), CivPosition(x + 4, 70, 4)), CivPosition(x + 2, 64, -1), BuildingPhase.ACTIVE, control = CivPosition(x + 2, 65, 2))
    val hall = building(BuildingType.TOWN_HALL, 0, 2)
    val house = building(BuildingType.HOUSE, 10)
    val builder = building(BuildingType.BUILDER_HUT, -10)
    val forest = building(BuildingType.FORESTER, 20)
    return Settlement(UUID.randomUUID(), player.uniqueId, "Oak Town", player.world.uid, CivPosition(0, 64, 0), 64,
        "medieval-oak", Stock(), listOf(hall, house, builder, forest),
        listOf(CivWorker(workerId(0), "Builder", builder.id, nextDeliveryAt = 10_000),
            CivWorker(workerId(1), "Resident A", null), CivWorker(workerId(2), "Resident B", null),
            CivWorker(workerId(3), "Forester", null)), inventory = CivInventory.starting(Stock(96, 96, 24)))
}

/** Opt-in source export for the shared native dialog renderer; no UI behavior is mirrored here. */
private fun exportUiScreen(directory: String, id: String, screen: PaperDialogScreen, viewport: List<Int> = listOf(854, 480)) {
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
        "viewport" to viewport, "variants" to listOf(mapOf("id" to "example"))) +
        if (inputs.isEmpty()) emptyMap() else mapOf("inputs" to inputs)
    val out = Path.of(directory)
    Files.createDirectories(out)
    Files.writeString(out.resolve("$id.json"), GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(
        mapOf("content" to content, "dialog" to dialog, "sourceSha256" to uiSourceHashes(),
            "components" to mapOf("id" to screen.id, "body" to screen.body.map {
                mapOf("text" to GsonComponentSerializer.gson().serializeToTree(it.text), "width" to it.width)
            }, "actions" to screen.buttons.map { it.id.value }),
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
    "src/main/kotlin/ru/arc/civs/CivsDialogPresentation.kt", "src/main/resources/config.yml",
    "src/main/kotlin/ru/arc/civs/CivsResidentLife.kt", "src/main/kotlin/ru/arc/civs/CivsResidents.kt",
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
