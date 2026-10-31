package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogDecoration
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale
import java.util.UUID
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.BUILDING_OVERVIEW_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.DELETE_NAME
import ru.arc.civs.CivsUi.Companion.MEMBER
import ru.arc.civs.CivsUi.Companion.WHITE
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.PEOPLE
import ru.arc.civs.CivsUi.Companion.STOCK
import ru.arc.civs.CivsUi.Companion.NAVIGATION
import ru.arc.civs.CivsUi.Companion.DANGER

internal fun CivsUi.sharedStock(player: Player, city: Settlement, compact: Boolean = false): PaperDialogBody {
    val rows = Resource.entries.map { text.resource(player, it) to tint(text.literal(city.stock[it]), STOCK) } +
        CivIndustryProduct.entries.filter { (city.autonomy?.products?.get(it) ?: 0L) > 0 }.map {
            industryProductName(player, it) to tint(text.literal(city.autonomy?.products?.get(it) ?: 0L), STOCK)
        }
    val useRows = compact || rows.size > 3
    return presentation.facts(rows, DialogTables.PanelSkin.EVERGREEN,
        if (useRows) DialogTables.FactsLayout.ROWS else DialogTables.FactsLayout.METRICS, if (useRows) 2 else 3)
}


internal fun CivsUi.townHallBodies(player: Player, city: Settlement, heading: Component? = null): List<PaperDialogBody> = buildList {
    val status = city.autonomy?.let { if (it.paused) CivsAutonomyView("paused") else host.autonomyStatus(city.id) }
    val construction = city.construction
    val header = listOfNotNull(heading, status?.let { DialogDecoration.stateBadge(autonomyStage(player, it),
        if (it.stage in setOf("paused", "resources", "blocked", "unhealthy")) DialogDecoration.StateTone.ATTENTION
        else DialogDecoration.StateTone.INFO) },
        tr(player, "common.detail", "label" to tr(player, "label.unassigned"),
            "value" to city.workers.count { it.assignment == null && !CivsResidents.isChild(it) }),
        if (construction == null) tr(player, "city.no-construction") else null,
    )
    add(presentation.panel(Component.join(Component.newline(), header), DialogTables.PanelSkin.NAVY))
    add(presentation.facts(listOf(
        presentation.field(DialogDecoration.FieldIcon.PLAYER, tr(player, "label.population")) to tint(tr(player, "common.fraction",
            "value" to city.workers.size, "limit" to CivsRules.residentLimit(city)), PEOPLE),
        presentation.field(DialogDecoration.FieldIcon.HOMES, tr(player, "label.beds")) to tint(text.literal(CivsRules.beds(city)), PEOPLE),
        presentation.field(DialogDecoration.FieldIcon.LANDS, tr(player, "label.buildings")) to tint(text.literal(CivsRules.buildingCount(city)), LAND),
    ), DialogTables.PanelSkin.NAVY, DialogTables.FactsLayout.METRICS, 3))
    if (construction != null) {
        val planned = construction.plannedBuilding ?: city.building(construction.buildingId)
        val label = tr(player, "common.detail", "label" to tr(player, "label.construction"),
            "value" to (planned?.let { buildingName(player, it.type, construction.targetLevel) } ?: tr(player, "buildings.generic")))
        if (construction.workRequired > 0) add(presentation.progress(label,
            construction.workDone, construction.workRequired, tr(player, "common.fraction",
                "value" to construction.workDone, "limit" to construction.workRequired)))
        else add(presentation.panel(label, DialogTables.PanelSkin.NAVY))
        construction.failure?.let { add(presentation.panel(text.reason(player, it), DialogTables.PanelSkin.COCOA)) }
    }
    val goal = if (city.autonomy != null) host.nextAutonomyGoal(city.id) else null
    nextExpansion(player, city, goal)?.let { add(body(Component.empty())); add(it) }
    if (city.autonomy == null && city.constructionQueue.orEmpty().isEmpty()) add(body(tint(nextStep(player, city), STOCK)))
    status?.reason?.let { if (it != city.construction?.failure) add(body(text.reason(player, it))) }
    if (goal == null && status != null && Resource.entries.any { status.missing[it] > 0 }) add(body(tr(player,
        "autonomy.missing", "resources" to Component.join(text.literal(" · "), Resource.entries.filter { status.missing[it] > 0 }.map {
            tr(player, "common.detail", "label" to text.resource(player, it), "value" to status.missing[it])
        }))))
}


internal fun CivsUi.showOverview(player: Player, feedback: Component? = null) {
    val city = city(player) ?: return
    val sleep = city.workers.isNotEmpty() && city.workers.all {
        host.workerStatus(it.id).substringAfterLast('.') in setOf("sleeping", "no-viewers")
    }
    val construction = city.construction
    val hall = city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
    val expansion = nextExpansion(player, city, if (city.autonomy != null) host.nextAutonomyGoal(city.id) else null)
    present(player, "civs.overview", "city.title", listOfNotNull(
        body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
        presentation.summary(Component.join(Component.newline(), listOf(
            tr(player, "city.summary-land", "level" to tint(text.literal(city.level()), PERSONAL),
                "radius" to tint(tr(player, "city.radius", "radius" to city.radius), NAVIGATION)),
            tr(player, "city.summary-residents", "population" to tint(tr(player, "common.fraction", "value" to city.workers.size,
                "limit" to CivsRules.residentLimit(city)), PEOPLE), "beds" to tint(text.literal(CivsRules.beds(city)), PEOPLE)),
            tr(player, "city.summary-activity", "buildings" to tint(tr(player, "common.fraction", "value" to CivsRules.buildingCount(city),
                "limit" to CivsRules.MAX_BUILDINGS), LAND), "activity" to tint(tr(player, if (sleep) "city.sleeping" else "city.active"),
                if (sleep) PERSONAL else COMMIT)),
            detail(player, "label.construction", constructionText(player, city)),
        ))),
        construction?.failure?.let { body(text.reason(player, it)) },
        presentation.divider(), expansion ?: presentation.paragraph(tint(nextStep(player, city), STOCK)),
        busyBody(player), feedback?.let(::body),
        hall?.takeIf { expansion == null && canUpgrade(city, it) }?.let { upgradeSummary(player, city, it) },
    ), buildList {
        add(button(player, "management", "management.open", PERSONAL) { showManagement(player) })
        add(button(player, "buildings", "buildings.open", LAND) { showBuildings(player) })
        add(button(player, "workers", "workers.open", PEOPLE) { showWorkers(player) })
        add(button(player, "research", "research.open", PERSONAL) { showResearch(player, city.id) })
        add(button(player, "industry", "industry.open", STOCK) { showIndustry(player, city.id) })
        if (hall != null && canUpgrade(city, hall)) add(button(player, "upgrade", "upgrades.hall-start", COMMIT, appearance = Action.UPGRADE) {
            perform(player, "civs.overview", "city.title", { host.upgradeBuilding(player, hall.id) },
                success = { showOverview(player) }, failure = { showOverview(player, it) }, workingKey = "upgrades.starting")
        })
        if (hall?.control != null && city.inventory != null && !host.cityBusy(player))
            add(button(player, "hall_inventory", "city.hall-inventory", STOCK) { openIntake(player, hall.id) }
                .copy(tooltip = tr(player, "city.hall-inventory-help")))
        add(button(player, "guide", "guide.open", NAVIGATION) { showGuide(player) })
    }, reopen = { showOverview(player) }, cityId = city.id)
}


internal fun CivsUi.showGuide(player: Player) {
    val city = city(player) ?: return
    present(player, "civs.guide", "guide.title", listOfNotNull(
        presentation.paragraph(tr(player, "city.boundary-help")),
        presentation.paragraph(tr(player, "city.sleep-rule")), presentation.divider(),
        presentation.callout(tr(player, "buildings.site-help")),
        presentation.paragraph(tr(player, "guide.population-help")),
        presentation.paragraph(tr(player, "guide.storage-help")),
    ), emptyList(), reopen = { showGuide(player) }, cityId = city.id)
}


internal fun CivsUi.showManagement(player: Player) {
    val city = city(player) ?: return
    present(player, "civs.management", "management.title", listOfNotNull(
        body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
        body(player, "management.help"),
        city.inventory?.let { inventory -> body(detail(player, "stock.mode-label",
            tr(player, "stock.mode.${inventory.mode.name.lowercase(Locale.ROOT)}"))) },
        city.inventory?.let { body(player, "stock.${it.mode.name.lowercase(Locale.ROOT)}-help") },
        busyBody(player),
    ), buildList {
        add(button(player, "stock", "stock.open", STOCK) { showStock(player) })
        add(button(player, "upgrades", "upgrades.open", PERSONAL) { showUpgrades(player) })
        add(button(player, "members", "members.open", PEOPLE) { showMembers(player) })
        if (CivSettlementSetting.entries.any { host.canChangeSettlementSetting(player, it) })
            add(button(player, "testing_settings", "testing-settings.open", PERSONAL, appearance = Action.SETTINGS) { showTestingSettings(player) })
        if (city.owner == player.uniqueId) add(button(player, "delete_city", "delete.open", DANGER) { showDeleteCity(player, city.id) })
        if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
    }, reopen = { showManagement(player) }, cityId = city.id)
}


internal fun CivsUi.showTestingSettings(player: Player, error: Component? = null) {
    val city = city(player) ?: return
    val allowed = CivSettlementSetting.entries.filter { host.canChangeSettlementSetting(player, it) }
    if (allowed.isEmpty()) return showManagement(player)
    val settings = city.settings ?: CivSettlementSettings()
    fun key(setting: CivSettlementSetting) = "testing-settings.${setting.name.lowercase(Locale.ROOT).replace('_', '-')}"
    present(player, "civs.testing-settings", "testing-settings.title", listOfNotNull(
        body(player, "testing-settings.help"),
        CivSettlementSetting.entries.filterNot { it in allowed }.takeIf { it.isNotEmpty() }?.let { readOnly ->
            facts(player, readOnly.map { setting ->
                val enabled = settings.enabled(setting)
                tr(player, key(setting)) to tint(tr(player, if (enabled) "testing-settings.enabled" else "testing-settings.disabled"),
                    if (enabled) COMMIT else WHITE)
            })
        }, error?.let(::body),
    ), allowed.map { setting ->
        val enabled = settings.enabled(setting)
        valueButton(player, setting.name.lowercase(Locale.ROOT), if (enabled) "common.selected" else "testing-settings.choice",
            tr(player, key(setting)), if (enabled) COMMIT else WHITE,
            Component.newline().append(tr(player, "${key(setting)}-help")).append(Component.newline()).append(Component.newline())
                .append(tr(player, if (enabled) "testing-settings.disable" else "testing-settings.enable")).append(Component.newline())) {
            if (host.city(player)?.id != city.id || !host.canChangeSettlementSetting(player, setting))
                showTestingSettings(player, text.reason(player, "not-authorized"))
            else perform(player, "civs.testing-settings", "testing-settings.title",
                { host.setSettlementSetting(player, setting, !enabled) },
                success = { showTestingSettings(player) }, failure = { showTestingSettings(player, it) }, workingKey = null)
        }
    }, reopen = { showTestingSettings(player) }, cityId = city.id)
}


internal fun CivsUi.showDeleteCity(player: Player, expectedCityId: UUID, initial: String = "", error: Component? = null) {
    val city = host.city(player)
    if (city == null || city.id != expectedCityId) {
        notice(player, "civs.delete-city", "delete.title", text.reason(player, "delete-city-changed")) {
            runtime.beginFlow(player)
            openEntry(player)
        }
        return
    }
    if (city.owner != player.uniqueId) return showEntry(player)
    val inventory = city.inventory
    val hasVirtualStock = inventory?.materials?.values.orEmpty().any { it > 0 } ||
        inventory?.buildingMaterials.orEmpty().values.any { stock -> stock.values.any { it > 0 } }
    val busy = host.cityBusy(player)
    var draft = initial
    present(player, "civs.delete-city", "delete.title", listOfNotNull(
        body(tr(player, "delete.city", "name" to city.name).color(DANGER)),
        body(player, "delete.world-help"), body(player, "delete.founding-help"),
        body(tr(player, "delete.stock-help").color(DANGER)),
        if (city.construction != null) body(player, "delete.construction-help") else null,
        body(player, "delete.name-help"),
        busyBody(player), error?.let { body(it.color(DANGER)) },
    ), buildList {
        if (hasVirtualStock) add(button(player, "stock", "delete.open-storage", STOCK) { showStock(player) })
        if (!busy) add(button(player, "delete", "delete.confirm", DANGER, appearance = Action.DELETE) { context ->
            val name = context.text(DELETE_NAME).orEmpty()
            draft = name
            if (name != city.name) showDeleteCity(player, expectedCityId, name, text.reason(player, "delete-name-mismatch"))
            else perform(player, "civs.delete-city", "delete.title", { host.deleteCity(player, expectedCityId, name) },
                success = {
                    runtime.beginFlow(player)
                    openEntry(player)
                    player.sendMessage(text.success(player, "delete.success", "name" to city.name))
                }, failure = { showDeleteCity(player, expectedCityId, name, it) })
        })
    }, inputs = listOf(
        PaperDialogTextInput(DELETE_NAME, tr(player, "delete.name-label"), initial = initial.take(32), width = 320, maxLength = 32),
    ), reopen = { showDeleteCity(player, expectedCityId, draft) }, cityId = city.id)
}


internal fun CivsUi.showMembers(player: Player, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val members = city.members.filter { it != city.owner }.sortedBy(UUID::toString)
    val current = page.coerceIn(0, lastPage(members.size))
    present(player, "civs.members", "members.title", listOfNotNull(
        body(player, "members.help"), error?.let(::body),
    ), buildList {
        add(valueButton(player, "owner", "common.destination", playerName(city, city.owner), PEOPLE,
            tr(player, "members.owner")) { showMember(player, city.owner) })
        members.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, member ->
            add(valueButton(player, "member_$index", "common.destination", playerName(city, member), PEOPLE) { showMember(player, member) })
        }
        if (city.owner == player.uniqueId) add(button(player, "add", "members.add", PERSONAL) { showAddMember(player) })
        pages(player, current, members.size) { showMembers(player, it) }
    }, reopen = { showMembers(player, current) }, cityId = city.id)
}


internal fun CivsUi.showAddMember(player: Player, initial: String = "", error: Component? = null) {
    val city = city(player) ?: return
    if (city.owner != player.uniqueId) return showMembers(player)
    present(player, "civs.member-add", "members.add-title", listOfNotNull(body(player, "members.add-help"), error?.let(::body)),
        listOf(button(player, "add", "members.add-confirm", COMMIT, appearance = Action.ADD) { context ->
            val name = context.text(MEMBER)?.trim().orEmpty()
            perform(player, "civs.member-add", "members.add-title", { host.addMember(player, name) },
                success = { showMembers(player) }, failure = { showAddMember(player, name, it) })
        }), inputs = listOf(PaperDialogTextInput(MEMBER, tr(player, "members.name-label"), initial = initial.take(16), width = 320, maxLength = 16)), cityId = city.id)
}


internal fun CivsUi.showMember(player: Player, member: UUID, error: Component? = null) {
    val city = city(player) ?: return
    if (member != city.owner && member !in city.members) return showMembers(player)
    present(player, "civs.member", "members.detail-title", listOfNotNull(
        body(if (member == city.owner) playerName(city, member) else tr(player, "members.detail", "name" to playerName(city, member))),
        if (member == city.owner) body(player, "members.owner") else null, error?.let(::body),
    ), if (city.owner == player.uniqueId && member != city.owner) listOf(button(player, "remove", "members.remove", DANGER, appearance = Action.DELETE) {
        perform(player, "civs.member", "members.detail-title", { host.removeMember(player, member) },
            success = { showMembers(player) }, failure = { showMember(player, member, it) })
    }) else emptyList(), reopen = { showMember(player, member) }, cityId = city.id)
}


internal fun CivsUi.autonomyDenied(player: Player) {
    runtime.beginFlow(player)
    roots[player.uniqueId] = "civs.autonomous-denied"
    present(player, "civs.autonomous-denied", "autonomy.title", listOf(body(text.reason(player, "not-authorized"))),
        emptyList(), reopen = { autonomyDenied(player) })
}


internal fun CivsUi.autonomyStage(player: Player, status: CivsAutonomyView): Component {
    val key = "autonomy.stage.${status.stage}"
    return tr(player, key.takeIf { it in text.keys("ru") } ?: "autonomy.stage.unavailable")
        .color(when (status.stage) {
            "blocked", "unhealthy" -> DANGER
            "paused", "resources" -> STOCK
            "complete" -> COMMIT
            else -> NAVIGATION
        })
}


internal fun CivsUi.autonomousCity(player: Player, cityId: UUID): Settlement? {
    if (!isAdmin(player)) {
        autonomyDenied(player)
        return null
    }
    val city = host.settlements().firstOrNull { it.id == cityId && it.autonomy != null }
    if (city == null) notice(player, "civs.autonomous-city", "autonomy.detail-title", tr(player, "autonomy.not-found"), "common.autonomous") {
        showAutonomous(player)
    }
    return city
}


internal fun CivsUi.showAutonomous(player: Player, page: Int = 0) {
    if (!isAdmin(player)) return autonomyDenied(player)
    val villages = if (host.ready()) host.settlements().filter { it.autonomy != null }.sortedBy { it.name } else emptyList()
    val current = page.coerceIn(0, lastPage(villages.size))
    present(player, "civs.autonomous", "autonomy.title", listOfNotNull(
        body(player, "autonomy.help"),
        if (!host.ready()) body(player, "common.starting") else if (villages.isEmpty()) body(player, "autonomy.empty") else
            body(tr(player, "autonomy.count", "count" to villages.size)),
        if (!host.foundingAllowed()) body(player, "network.view-only") else null,
        if (host.ready() && host.styles().isEmpty()) body(player, "founding.unavailable") else null,
    ), buildList {
        villages.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, city ->
            val status = host.autonomyStatus(city.id) ?: CivsAutonomyView(if (city.autonomy?.paused == true) "paused" else "unavailable")
            add(valueButton(player, "village_$index", "common.destination", text.literal(city.name), LAND,
                autonomyStage(player, status)) { showAutonomousCity(player, city.id) })
        }
        if (host.ready() && host.foundingAllowed() && host.styles().isNotEmpty())
            add(button(player, "create", "autonomy.create", COMMIT, appearance = Action.FOUND) {
                showStyles(player, autonomous = true)
            })
        pages(player, current, villages.size) { showAutonomous(player, it) }
    }, reopen = { showAutonomous(player, current) })
}


internal fun CivsUi.showAutonomousCity(player: Player, cityId: UUID, feedback: Component? = null) {
    val city = autonomousCity(player, cityId) ?: return
    val paused = city.autonomy!!.paused
    val status = if (paused) CivsAutonomyView("paused") else host.autonomyStatus(city.id) ?: CivsAutonomyView("unavailable")
    val target = status.target?.let { type -> status.level?.let { buildingName(player, type, it) } ?: text.building(player, type) }
    val missing = Resource.entries.filter { status.missing[it] > 0 }
    val nextGoal = host.nextAutonomyGoal(city.id)
    val completed = CivsAutonomy.profile.count { type ->
        city.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE && it.level >= minOf(2, type.maxLevel) }
    }
    present(player, "civs.autonomous-city", "autonomy.detail-title", listOfNotNull(
        presentation.summary(Component.join(Component.newline(), buildList {
            add(tint(text.literal(city.name), LAND))
            add(autonomyStage(player, status))
            if (city.construction != null) add(detail(player, "next-plan.current", constructionText(player, city)))
            else if (nextGoal == null) target?.let { add(tr(player, "autonomy.goal", "target" to it)) }
            add(tr(player, "autonomy.progress", "count" to completed, "total" to CivsAutonomy.profile.size))
        })),
        status.reason?.let { body(text.reason(player, it)) },
        nextExpansion(player, city, nextGoal),
        if (nextGoal == null && missing.isNotEmpty()) presentation.paragraph(tint(tr(player, "autonomy.missing", "resources" to
            Component.join(Component.text(" · "), missing.map { resource ->
                tr(player, "common.detail", "label" to text.resource(player, resource), "value" to status.missing[resource])
            })), STOCK)) else null,
        presentation.paragraph(Component.join(Component.newline(), listOf(
            tr(player, "autonomy.population", "buildings" to CivsRules.buildingCount(city), "workers" to city.workers.size,
                "assigned" to city.workers.count { it.assignment != null }),
        ))), feedback?.let(::body),
    ), buildList {
        add(button(player, "buildings", "buildings.open", LAND) { showAutonomousBuildings(player, city.id) })
        add(button(player, "workers", "workers.open", PEOPLE) { showAutonomousWorkers(player, city.id) })
        add(button(player, "research", "research.open", PERSONAL) { showResearch(player, city.id) })
        add(button(player, "industry", "industry.open", STOCK) { showIndustry(player, city.id) }
            .copy(tooltip = tr(player, "autonomy.resources-help")))
        if (!paused && city.construction?.phase == ConstructionPhase.PAUSED)
            add(button(player, "retry", "admin.retry", COMMIT, appearance = Action.START) {
                if (!isAdmin(player)) autonomyDenied(player)
                else perform(player, "civs.autonomous-city", "autonomy.detail-title", { host.retryConstruction(player, city.id) },
                    success = { showAutonomousCity(player, city.id) }, failure = { showAutonomousCity(player, city.id, it) })
            })
        add(button(player, "pause", if (paused) "autonomy.resume" else "autonomy.pause", if (paused) COMMIT else STOCK,
            appearance = if (paused) Action.START else null) {
            if (!isAdmin(player)) autonomyDenied(player)
            else perform(player, "civs.autonomous-city", "autonomy.detail-title",
                { host.setAutonomousPaused(player, city.id, !paused) },
                success = { showAutonomousCity(player, city.id) }, failure = { showAutonomousCity(player, city.id, it) })
        }.let { if (paused) it.copy(tooltip = tr(player, "autonomy.paused-help")) else it })
        add(button(player, "villages", "autonomy.open", PERSONAL) { showAutonomous(player) })
    }, reopen = { showAutonomousCity(player, city.id) }, cityId = city.id)
}


internal fun CivsUi.showAutonomousBuildings(player: Player, cityId: UUID, page: Int = 0) {
    val city = autonomousCity(player, cityId) ?: return
    val ordinary = city.buildings.filterNot { it.type.isInfrastructure }
    val current = page.coerceIn(0, lastPage(ordinary.size, BUILDING_OVERVIEW_PAGE_SIZE))
    val buildings = ordinary.drop(current * BUILDING_OVERVIEW_PAGE_SIZE).take(BUILDING_OVERVIEW_PAGE_SIZE)
    present(player, "civs.autonomous-buildings", "buildings.title", listOfNotNull(
        body(tint(text.literal(city.name), LAND)), body(player, "autonomy.buildings-help"), infrastructureSummary(player, city),
        if (buildings.isEmpty()) body(player, "autonomy.buildings-empty") else DialogTables.body(buildings.map { building -> listOf(
            tint(text.building(player, building.type), buildingColor(building.type)), tint(text.literal(building.level), PERSONAL),
            buildingSummaryStatus(player, city, building), tint(text.literal(city.workers.count { it.assignment == building.id }), PEOPLE),
        ) }, listOf("building", "level", "state", "workers").map { tr(player, "buildings.column-$it") },
            DialogTables.Frame.RARE, 400, listOf(5, 1, 5, 3)),
    ), buildList {
        pages(player, current, ordinary.size, BUILDING_OVERVIEW_PAGE_SIZE) { showAutonomousBuildings(player, city.id, it) }
    }, reopen = { showAutonomousBuildings(player, city.id, current) }, cityId = city.id)
}


internal fun CivsUi.showAutonomousWorkers(player: Player, cityId: UUID, page: Int = 0) {
    val city = autonomousCity(player, cityId) ?: return
    val current = page.coerceIn(0, lastPage(city.workers.size))
    val workers = city.workers.drop(current * PAGE_SIZE).take(PAGE_SIZE)
    present(player, "civs.autonomous-workers", "workers.title", listOfNotNull(
        body(tint(text.literal(city.name), LAND)), body(player, "autonomy.workers-help"),
        if (workers.isEmpty()) body(player, "autonomy.workers-empty") else presentation.paragraph(
            Component.join(Component.newline(), workers.map { worker ->
                tr(player, "common.detail", "label" to tint(text.literal(worker.name), PEOPLE), "value" to workerStatus(player, worker))
            })),
    ), buildList {
        workers.forEachIndexed { index, worker ->
            add(valueButton(player, "worker_$index", "common.destination", text.literal(worker.name), PEOPLE,
                workerStatus(player, worker)) { showWorker(player, worker.id, cityId = city.id) })
        }
        pages(player, current, city.workers.size) { showAutonomousWorkers(player, city.id, it) }
    }, reopen = { showAutonomousWorkers(player, city.id, current) }, cityId = city.id)
}


internal fun CivsUi.showAdmin(player: Player, page: Int = 0, error: Component? = null) {
    if (!isAdmin(player)) return showEntry(player)
    val cities = host.settlements().sortedBy { it.name }
    val current = page.coerceIn(0, lastPage(cities.size))
    present(player, "civs.admin", "admin.title", listOfNotNull(table(listOf(
        tr(player, "label.state") to tr(player, if (host.ready()) "admin.ready" else "admin.starting"),
        tr(player, "label.cities") to text.literal(cities.size),
        tr(player, "label.active-npcs") to tr(player, "common.fraction", "value" to host.activeNpcCount(),
            "limit" to plugin.config.getInt("maximum-active-npcs", CivsSettings.MAXIMUM_NPCS)),
    ), frame = DialogTables.Frame.RARE), error?.let(::body)), buildList {
        add(button(player, "autonomous", "autonomy.open", LAND, appearance = Action.FOUND) { showAutonomous(player) })
        cities.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, city ->
            add(valueButton(player, "city_$index", "common.destination", text.literal(city.name)) { showAdminCity(player, city.id) })
        }
        add(button(player, "reload", "admin.reload", PERSONAL) { showReload(player) })
        pages(player, current, cities.size) { showAdmin(player, it) }
    }, reopen = { showAdmin(player, current) })
}


internal fun CivsUi.showAdminCity(player: Player, cityId: UUID, error: Component? = null) {
    if (!isAdmin(player)) return showEntry(player)
    val city = host.settlements().firstOrNull { it.id == cityId } ?: return showAdmin(player)
    if (city.autonomy != null) return showAutonomousCity(player, cityId, error)
    present(player, "civs.admin-city", "admin.city-title", listOfNotNull(
        body(text.literal(city.name)), table(listOf(
            tr(player, "label.owner") to playerName(city, city.owner),
            tr(player, "label.city-id") to text.literal(city.id),
            tr(player, "label.construction") to constructionText(player, city),
        ), frame = DialogTables.Frame.RARE), city.construction?.failure?.let { body(text.reason(player, it)) }, error?.let(::body),
    ), buildList {
        if (city.construction?.phase == ConstructionPhase.PAUSED) add(button(player, "retry", "admin.retry", COMMIT, appearance = Action.START) {
            perform(player, "civs.admin-city", "admin.city-title", { host.retryConstruction(player, cityId) },
                success = { showAdminCity(player, cityId) }, failure = { showAdminCity(player, cityId, it) })
        })
    }, reopen = { showAdminCity(player, cityId) }, cityId = city.id)
}


internal fun CivsUi.showReload(player: Player, error: Component? = null) {
    if (!isAdmin(player)) return showEntry(player)
    present(player, "civs.admin-reload", "admin.reload-title", listOfNotNull(body(player, "admin.reload-help"), error?.let(::body)),
        listOf(button(player, "confirm", "admin.reload-confirm", COMMIT, appearance = Action.RESET) {
            perform(player, "civs.admin-reload", "admin.reload-title", { host.reload(player) },
                success = { showAdmin(player) }, failure = { showReload(player, it) })
        }), reopen = { showReload(player) })
}


internal fun CivsUi.nextStep(player: Player, city: Settlement): Component {
    val key = when {
        city.construction?.phase == ConstructionPhase.PAUSED -> "city.next-paused"
        city.construction?.phase == ConstructionPhase.WAITING_MATERIALS -> "city.next-materials"
        city.construction != null && city.workers.none { worker -> worker.assignment?.let(city::building)?.let { CivsRules.isBuilder(city, it) } == true } ->
            if (CivsRules.legacyHallBuilder(city)) "city.next-legacy-builder" else "city.next-builder"
        city.construction != null -> "city.next-building"
        city.workers.isEmpty() && (city.immigration?.remaining ?: 0) > 0 -> "city.next-arrival"
        city.buildings.none { it.type == BuildingType.BUILDER_HUT } && !CivsRules.legacyHallBuilder(city) -> "city.next-builder-hut"
        city.buildings.any { it.phase == BuildingPhase.SITE } -> "city.next-start"
        city.buildings.none { it.type == BuildingType.WAREHOUSE } -> "city.next-warehouse"
        listOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM).any { type -> city.buildings.none { it.type == type } } -> "city.next-production"
        city.workers.any { it.assignment == null && !CivsResidents.isChild(it) } -> "city.next-assign"
        city.workers.size < CivsRules.residentLimit(city) && city.workers.size >= CivsRules.beds(city) -> "city.next-house"
        city.level() < 3 -> "city.next-upgrade"
        else -> "city.next-grow"
    }
    return tr(player, key)
}

/** One accepted order or one planner projection; ordinary towns never imply automatic growth. */

internal fun CivsUi.nextExpansion(player: Player, city: Settlement, projected: CivAutonomyGoal?): PaperDialogBody? {
    val current = city.construction
    val queued = city.constructionQueue.orEmpty().asSequence().filter { it != current?.buildingId }
        .mapNotNull(city::building).firstOrNull()
    val goal = projected?.takeUnless { current != null && it.buildingId == current.buildingId && it.targetLevel == current.targetLevel }
    if (queued == null && city.autonomy == null) return null
    val heading = when {
        queued != null -> "queued-title"
        current != null -> "after-current"
        else -> "title"
    }
    val lines = mutableListOf(tint(tr(player, "next-plan.$heading"), LAND))
    if (queued == null && goal == null) lines += tr(player, "next-plan.empty")
    else {
        val upgrade = queued?.let { it.phase == BuildingPhase.ACTIVE } ?: (goal!!.targetLevel > 1)
        val type = queued?.type ?: goal!!.type
        val level = queued?.let { if (upgrade) it.level + 1 else it.level } ?: goal!!.targetLevel
        lines += tr(player, if (upgrade) "next-plan.upgrade" else "next-plan.build",
            "building" to buildingName(player, type, level))
        val (state, tone) = when {
            city.autonomy?.paused == true -> "paused" to DialogDecoration.StateTone.ATTENTION
            queued != null -> "queued" to DialogDecoration.StateTone.INFO
            goal!!.ready -> "ready" to DialogDecoration.StateTone.SUCCESS
            else -> "resources" to DialogDecoration.StateTone.ATTENTION
        }
        lines += DialogDecoration.stateBadge(tr(player, "next-plan.$state"), tone)
        if (queued == null && goal != null && !goal.ready) lines += tr(player, "next-plan.missing", "resources" to
            Component.join(text.literal(" · "), Resource.entries.filter { goal.missing[it] > 0 }.map { resource ->
                tr(player, "common.detail", "label" to text.resource(player, resource), "value" to goal.missing[resource])
            }))
    }
    return presentation.panel(Component.join(Component.newline(), lines), DialogTables.PanelSkin.EVERGREEN)
}
