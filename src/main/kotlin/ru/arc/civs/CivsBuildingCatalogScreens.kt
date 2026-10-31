package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.menu.DialogTables
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogBody
import java.util.Locale
import java.util.UUID
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.BUILDINGS_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.BUILDING_OVERVIEW_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.WHITE
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.PEOPLE

internal fun CivsUi.showBuildings(player: Player, page: Int = 0) {
    val city = city(player) ?: return
    val ordinary = city.buildings.filterNot { it.type.isInfrastructure }
    val current = page.coerceIn(0, lastPage(ordinary.size, BUILDING_OVERVIEW_PAGE_SIZE))
    val buildings = ordinary.drop(current * BUILDING_OVERVIEW_PAGE_SIZE).take(BUILDING_OVERVIEW_PAGE_SIZE)
    present(player, "civs.buildings", "buildings.title", listOfNotNull(
        body(player, "buildings.help"), infrastructureSummary(player, city),
        if (buildings.isNotEmpty()) DialogTables.body(buildings.map { building -> listOf(
            tint(text.building(player, building.type), buildingColor(building.type)),
            tint(text.literal(building.level), PERSONAL),
            buildingSummaryStatus(player, city, building),
            tint(tr(player, "common.fraction", "value" to city.workers.count { it.assignment == building.id },
                "limit" to CivsRules.slots(city, building)), PEOPLE),
        ) }, listOf("building", "level", "state", "workers").map { tr(player, "buildings.column-$it") },
            DialogTables.Frame.RARE, 400, listOf(5, 1, 5, 3)) else null,
    ), buildList {
        buildings.forEachIndexed { index, building ->
            add(valueButton(player, "building_$index", "common.destination", buildingName(player, building.type, building.level), buildingColor(building.type),
                buildingStatus(player, city, building)) {
                showBuilding(player, building.id)
            })
        }
        if (CivsRules.buildingCount(city) < CivsRules.MAX_BUILDINGS) add(button(player, "catalog", "buildings.catalog", PERSONAL) { showCatalog(player) })
        pages(player, current, ordinary.size, BUILDING_OVERVIEW_PAGE_SIZE) { showBuildings(player, it) }
    }, reopen = { showBuildings(player, current) }, cityId = city.id)
}


internal fun CivsUi.infrastructureSummary(player: Player, city: Settlement): PaperDialogBody? {
    val sections = city.buildings.filter { it.type.isInfrastructure }.groupBy(CivBuilding::type)
    if (sections.isEmpty()) return null
    return presentation.paragraph(Component.join(Component.newline(), listOf(tint(tr(player, "buildings.infrastructure"), LAND)) +
        listOf(BuildingType.ROAD, BuildingType.WALL, BuildingType.GATE).mapNotNull { type -> sections[type]?.let { parts ->
            tr(player, "buildings.infrastructure-count", "building" to text.building(player, type),
                "ready" to parts.count { it.phase == BuildingPhase.ACTIVE }, "total" to parts.size)
        } }))
}


internal fun CivsUi.showQueue(player: Player, page: Int = 0) {
    val city = city(player) ?: return
    val queued = city.constructionQueue.orEmpty()
    val current = page.coerceIn(0, lastPage(queued.size))
    present(player, "civs.queue", "queue.title", listOfNotNull(
        city.construction?.let { presentation.summary(Component.join(Component.newline(),
            listOf(tr(player, "queue.active").color(LAND), constructionText(player, city)))) }
            ?: presentation.paragraph(tr(player, "queue.no-active")),
        presentation.divider(),
        presentation.paragraph(tr(player, if (queued.isEmpty()) "queue.empty" else "queue.waiting", "count" to queued.size)),
    ), buildList {
        city.construction?.let { order ->
            add(button(player, "active", "queue.active-open", LAND) { showBuilding(player, order.buildingId) })
        }
        queued.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, id ->
            city.building(id)?.let { building ->
                add(valueButton(player, "queue_$index", "common.destination", tr(player, "queue.entry",
                    "position" to current * PAGE_SIZE + index + 1,
                    "building" to buildingName(player, building.type,
                        if (building.phase == BuildingPhase.ACTIVE) building.level + 1 else building.level)), LAND,
                    buildingStatus(player, city, building)) { showBuilding(player, id) })
            }
        }
        pages(player, current, queued.size) { showQueue(player, it) }
    }, reopen = { showQueue(player, current) }, cityId = city.id)
}


internal fun CivsUi.showCatalog(player: Player, page: Int = 0) {
    val city = city(player) ?: return
    val types = BuildingType.entries.filter { it != BuildingType.TOWN_HALL && !it.isInfrastructure }
    val current = page.coerceIn(0, lastPage(types.size, BUILDINGS_PAGE_SIZE))
    present(player, "civs.catalog", "buildings.catalog-title", listOf(body(player, "buildings.catalog-help")),
        buildList {
            types.drop(current * BUILDINGS_PAGE_SIZE).take(BUILDINGS_PAGE_SIZE).forEachIndexed { index, type ->
                add(valueButton(player, "offer_$index", "common.destination", text.building(player, type), buildingColor(type)) { showOffer(player, type) })
            }
            pages(player, current, types.size, BUILDINGS_PAGE_SIZE) { showCatalog(player, it) }
        }, reopen = { if (city(player) != null) showCatalog(player, current) }, cityId = city.id)
}


internal fun CivsUi.showOffer(player: Player, type: BuildingType, error: Component? = null) {
    val city = city(player) ?: return
    val requiredResearch = CivsResearch.required(type, 1)?.takeUnless { CivsResearch.has(city, it) }
    val room = if (type == BuildingType.DECORATION) city.buildings.count { it.type == type } < CivsRules.decorationLimit(city)
        else CivsRules.buildingCount(city) < CivsRules.MAX_BUILDINGS
    val eligible = room && requiredResearch == null
    present(player, "civs.offer", "buildings.offer-title", listOfNotNull(
        body(buildingName(player, type, 1)), body(player, "building-purpose.${type.name.lowercase(Locale.ROOT)}"),
        if (type !in setOf(BuildingType.QUARRY, BuildingType.SMELTERY, BuildingType.CRUSHER) &&
            (type in setOf(BuildingType.BUILDER_HUT, BuildingType.WAREHOUSE, BuildingType.SAWMILL) || CivsProduction.options(type).isNotEmpty()))
            body(player, "buildings.workplaces-levels") else null,
        body(player, "buildings.site-help"),
        requiredResearch?.let { body(tr(player, "research.building-required", "name" to researchName(player, it))) },
        if (!room) body(player, "reason.building-limit") else null,
        error?.let(::body),
    ), buildList {
        if (requiredResearch != null) add(button(player, "research", "research.open", PERSONAL) { showResearchDetail(player, city.id, requiredResearch) })
        if (eligible) add(button(player, "prepare", "buildings.prepare", COMMIT, appearance = Action.FOUND) { showBuildingStyles(player, type) })
    }, reopen = { showOffer(player, type) }, cityId = city.id)
}


internal fun CivsUi.showBuildingStyles(player: Player, type: BuildingType, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val styles = host.styles(type)
    val current = page.coerceIn(0, lastPage(styles.size))
    present(player, "civs.building-styles", "buildings.style-title", listOfNotNull(
        body(buildingName(player, type, 1)),
        body(player, "buildings.style-help"),
        if (styles.isEmpty()) body(player, "buildings.styles-empty") else null,
        error?.let(::body),
    ), buildList {
        styles.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, style ->
            add(valueButton(player, "style_$index", "common.choice", text.style(player, style),
                if (style == city.style) LAND else WHITE) {
                val choices = host.blueprintChoices(type, style)
                if (type == BuildingType.DECORATION && choices.size > 1) showBlueprintCategories(player, style)
                else if (choices.size > 1) showBlueprintChoices(player, type, style)
                else beginSelection(player, {
                    if (choices.size == 1) host.beginBlueprintSelection(player, type, style, choices.single().id)
                    else host.beginBuildingSelection(player, type, style)
                }) { showBuildingStyles(player, type, current, it) }
            })
        }
        pages(player, current, styles.size) { showBuildingStyles(player, type, it) }
    }, reopen = { showBuildingStyles(player, type, current) }, cityId = city.id)
}


internal fun CivsUi.showBlueprintCategories(player: Player, style: String) {
    val city = city(player) ?: return
    val categories = host.blueprintChoices(BuildingType.DECORATION, style).groupBy { it.category }.toSortedMap()
    if (categories.size == 1) return showBlueprintChoices(player, BuildingType.DECORATION, style, category = categories.keys.single())
    present(player, "civs.blueprint-categories", "buildings.category-title", listOf(
        body(text.style(player, style)), body(player, "buildings.category-help")), buildList {
        categories.entries.forEachIndexed { index, (category, entries) ->
            add(valueButton(player, "category_$index", "common.destination", tr(player, "blueprint-category.$category"),
                buildingColor(BuildingType.DECORATION), tr(player, "buildings.variant-count", "count" to entries.size)) {
                showBlueprintChoices(player, BuildingType.DECORATION, style, category = category)
            })
        }
    }, reopen = { showBlueprintCategories(player, style) }, cityId = city.id)
}


internal fun CivsUi.showBlueprintChoices(player: Player, type: BuildingType, style: String, page: Int = 0,
    error: Component? = null, category: String? = null) {
    val city = city(player) ?: return
    val choices = host.blueprintChoices(type, style).filter { category == null || it.category == category }
    val current = page.coerceIn(0, lastPage(choices.size))
    present(player, "civs.blueprint-choices", "buildings.variant-title", listOfNotNull(
        body(Component.join(text.literal(" · "), text.building(player, type), text.style(player, style))),
        category?.let { body(tr(player, "blueprint-category.$it")) },
        body(player, "buildings.variant-help"), error?.let(::body),
    ), buildList {
        choices.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, choice ->
            val number = current * PAGE_SIZE + index + 1
            val sized = choice.size.size == 3
            val label = if (sized) tr(player, "buildings.variant-choice", "number" to number,
                "width" to choice.size[0], "depth" to choice.size[2]) else tr(player, "buildings.variant-number", "number" to number)
            val hint = tr(player, "buildings.variant-levels", "level" to choice.maxLevel).let { levels ->
                if (sized) levels.append(Component.newline()).append(tr(player, "buildings.variant-size",
                    "width" to choice.size[0], "depth" to choice.size[2], "height" to choice.size[1])) else levels
            }
            add(valueButton(player, "variant_$index", "common.destination", label, buildingColor(type), hint) {
                beginSelection(player, { host.beginBlueprintSelection(player, type, style, choice.id) }) {
                    showBlueprintChoices(player, type, style, current, it, category)
                }
            })
        }
        pages(player, current, choices.size) { showBlueprintChoices(player, type, style, it, category = category) }
    }, reopen = { showBlueprintChoices(player, type, style, current, category = category) }, cityId = city.id)
}


internal fun CivsUi.showUpgrades(player: Player, page: Int = 0) {
    val city = city(player) ?: return
    val buildings = city.buildings.filter { it.level < host.maximumBuildingLevel(it) && it.phase == BuildingPhase.ACTIVE }
    val current = page.coerceIn(0, lastPage(buildings.size))
    present(player, "civs.upgrades", "upgrades.title", listOf(body(player,
        if (buildings.isEmpty()) "upgrades.complete" else "upgrades.help")), buildList {
        buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
            add(valueButton(player, "upgrade_$index", "common.destination", buildingName(player, building.type, building.level), PERSONAL) {
                showUpgrade(player, building.id)
            })
        }
        pages(player, current, buildings.size) { showUpgrades(player, it) }
    }, reopen = { showUpgrades(player, current) }, cityId = city.id)
}


internal fun CivsUi.showUpgrade(player: Player, buildingId: UUID, error: Component? = null) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (building.level >= host.maximumBuildingLevel(building)) return showBuilding(player, buildingId)
    val target = building.level + 1
    val hallRequired = building.type != BuildingType.TOWN_HALL && target > city.level()
    val queued = queuePosition(city, building.id)
    val buildingInProgress = city.construction?.buildingId == building.id
    val requiredResearch = CivsResearch.required(building.type, target)?.takeUnless { CivsResearch.has(city, it) }
    val eligible = !hallRequired && !buildingInProgress && queued == null && building.phase == BuildingPhase.ACTIVE && requiredResearch == null
    present(player, "civs.upgrade", "upgrades.detail-title", listOfNotNull(
        body(buildingName(player, building.type, building.level)),
        upgradeSummary(player, city, building), body(player, "upgrades.materials-help"),
        requiredResearch?.let { body(tr(player, "research.upgrade-required", "name" to researchName(player, it), "level" to target)) },
        when {
            queued != null -> body(tr(player, "queue.materials-help", "position" to queued))
            buildingInProgress -> body(player, "reason.construction-busy")
            hallRequired -> body(tr(player, "upgrades.hall-required", "level" to target))
            else -> null
        }, error?.let(::body),
    ), buildList {
        if (requiredResearch != null) add(button(player, "research", "research.open", PERSONAL) { showResearchDetail(player, city.id, requiredResearch) })
        if (eligible) add(button(player, "upgrade", "upgrades.start", COMMIT, appearance = Action.UPGRADE) {
            perform(player, "civs.upgrade", "upgrades.detail-title", { host.upgradeBuilding(player, buildingId) },
                success = { showBuilding(player, buildingId) }, failure = { showUpgrade(player, buildingId, it) },
                workingKey = "upgrades.starting")
        })
    }, reopen = { showUpgrade(player, buildingId) }, cityId = city.id)
}
