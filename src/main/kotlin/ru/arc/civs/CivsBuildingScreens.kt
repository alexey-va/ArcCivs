package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogDecoration
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogBody
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.CARPENTRY_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.WHITE
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.PEOPLE
import ru.arc.civs.CivsUi.Companion.STOCK
import ru.arc.civs.CivsUi.Companion.NAVIGATION
import ru.arc.civs.CivsUi.Companion.DANGER

internal fun CivsUi.showBuildingInspection(player: Player, cityId: UUID, buildingId: UUID, error: Component? = null) {
    val city = try { host.inspectBuilding(player, cityId, buildingId) } catch (failure: Exception) {
        present(player, "civs.building-inspection", "autonomy.building-title", listOf(body(text.failure(player, failure))),
            emptyList(), reopen = { showBuildingInspection(player, cityId, buildingId) })
        return
    }
    if (city.autonomy == null) return openBuilding(player, cityId, buildingId)
    val building = city.building(buildingId) ?: return
    val workers = city.workers.filter { it.assignment == buildingId }
    val residents = city.workers.filter { it.homeId == buildingId }
    val bodies = buildList {
        if (building.type == BuildingType.TOWN_HALL) {
            addAll(townHallBodies(player, city, buildingName(player, building.type, building.level)
                .append(text.literal(" · ")).append(tint(text.literal(city.name), LAND))))
        } else {
            add(body(buildingName(player, building.type, building.level)))
            val details = presentation.facts(buildList {
                add(presentation.field(DialogDecoration.FieldIcon.LANDS, tr(player, "autonomy.village-label")) to tint(text.literal(city.name), LAND))
                add(tr(player, "label.state") to DialogDecoration.stateBadge(buildingStatus(player, city, building), DialogDecoration.StateTone.INFO))
                if (building.type.isResidence) {
                    add(presentation.field(DialogDecoration.FieldIcon.HOMES, tr(player, "label.population")) to
                        tint(text.literal(residents.size), PEOPLE))
                    add(tr(player, "label.beds") to text.literal(CivsPopulation.houseBeds(building)))
                }
                if (building.type != BuildingType.HOUSE && CivsRules.slots(city, building) > 0)
                    add(tr(player, "label.workers") to tint(tr(player, "common.fraction", "value" to workers.size,
                        "limit" to CivsRules.slots(city, building)), PEOPLE))
                if (building.type == BuildingType.FORESTER) add(tr(player, "forestry.species-label") to
                    treeSpecies(player, requireNotNull(CivsProduction.material(building))))
            }, if (building.type.isResidence) DialogTables.PanelSkin.COCOA else DialogTables.PanelSkin.NAVY)
            add(details)
            if (building.type.isResidence) add(residentRoster(player, residents, details.width))
            if (building.type != BuildingType.HOUSE && workers.isNotEmpty()) add(body(Component.join(Component.newline(), workers.map {
                tr(player, "common.detail", "label" to tint(text.literal(it.name), PEOPLE), "value" to workerStatus(player, it))
            })))
        }
        if (building.type == BuildingType.WAREHOUSE || building.type == BuildingType.TOWN_HALL) {
            if (building.type == BuildingType.TOWN_HALL) add(body(Component.empty()))
            add(presentation.heading(tint(tr(player, if (city.inventory != null) "autonomy.local-stock"
                else "autonomy.shared-stock"), STOCK)))
            add(body(Component.empty()))
            if (city.inventory == null) add(sharedStock(player, city, compact = building.type == BuildingType.TOWN_HALL))
            else {
                val local = runCatching { host.inspectionMaterials(player, city.id, buildingId) }
                val rows = local.getOrDefault(emptyMap()).toSortedMap().entries.take(PAGE_SIZE)
                if (rows.isNotEmpty()) add(presentation.facts(rows.map { (material, count) ->
                    materialName(player, material) to tint(text.literal(count), STOCK)
                }, DialogTables.PanelSkin.EVERGREEN))
                else add(presentation.panel(local.exceptionOrNull()?.let { text.failure(player, it) }
                    ?: tr(player, "stock.empty"), DialogTables.PanelSkin.EVERGREEN))
                add(presentation.panel(tr(player, "stock.physical-help"), DialogTables.PanelSkin.EVERGREEN))
            }
        }
        val barrelHelp = tr(player, if (isAdmin(player)) "autonomy.barrel-edit-help" else "autonomy.barrel-read-help")
        if (building.type != BuildingType.TOWN_HALL) add(body(barrelHelp))
        error?.let { add(body(it)) }
    }
    present(player, "civs.building-inspection", "autonomy.building-title",
        if (building.type == BuildingType.TOWN_HALL) listOf(presentation.compact(bodies)) else bodies, buildList {
        if (building.type == BuildingType.TOWN_HALL && isAdmin(player)) {
            add(button(player, "workers", "workers.open", PEOPLE) { showAutonomousWorkers(player, cityId) })
            add(button(player, "buildings", "buildings.open", LAND) { showAutonomousBuildings(player, cityId) })
        }
        if (building.type == BuildingType.UNIVERSITY)
            add(button(player, "research", "research.open", PERSONAL) { showResearch(player, cityId) })
        if (building.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.BLACKSMITH, BuildingType.QUARRY))
            add(button(player, "industry", "industry.open", STOCK) { showIndustryBuilding(player, cityId, buildingId) })
        if (building.type == BuildingType.FORESTER && isAdmin(player))
            add(button(player, "production", "forestry.open", LAND) { showForesterSpecies(player, cityId, buildingId) })
        add(button(player, "inventory", "autonomy.barrel-open", STOCK) { openInspectionInventory(player, cityId, buildingId) }
            .copy(tooltip = tr(player, if (isAdmin(player)) "autonomy.barrel-edit-help" else "autonomy.barrel-read-help")))
    }, reopen = { showBuildingInspection(player, cityId, buildingId) }, cityId = city.id)
}


internal fun CivsUi.showBuilding(player: Player, buildingId: UUID, error: Component? = null) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (building.type.isInfrastructure) {
        present(player, "civs.building", "buildings.detail-title", listOf(
            body(tint(text.building(player, building.type), buildingColor(building.type))),
            body(player, "building-purpose.${building.type.name.lowercase(Locale.ROOT)}"),
            facts(player, listOf(tr(player, "label.state") to buildingSummaryStatus(player, city, building),
                tr(player, "label.position") to coordinates(player, building.entrance))),
        ), emptyList(), reopen = { showBuilding(player, buildingId) }, cityId = city.id)
        return
    }
    val assigned = city.workers.count { it.assignment == buildingId }
    val residents = city.workers.filter { it.homeId == buildingId }
    val request = host.buildingRequest(player, buildingId)
    val requestTool = requestTool(request)
    val busy = host.cityBusy(player)
    val queued = queuePosition(city, buildingId) != null
    val legacyControl = building.type != BuildingType.DECORATION && building.phase == BuildingPhase.ACTIVE &&
        (building.control == null || building.control == building.anchor)
    val acceptedToolOrders = if (building.type == BuildingType.BLACKSMITH) runCatching { host.carpentry(player, buildingId) }
        .getOrNull()?.orders.orEmpty() else emptyList()
    present(player, "civs.building", "buildings.detail-title", listOfNotNull(
        body(buildingName(player, building.type, building.level)),
        body(player, if (building.type == BuildingType.TOWN_HALL && CivsRules.isBuilder(city, building))
            "buildings.legacy-builder-help" else "building-purpose.${building.type.name.lowercase(Locale.ROOT)}"),
        presentation.facts(buildList {
            add(tr(player, "label.level") to tint(text.literal(building.level), PERSONAL))
            if (building.type != BuildingType.DECORATION)
                add(tr(player, "label.control") to (building.control?.let { coordinates(player, it) } ?: tr(player, "control.unset")))
            add(tr(player, "label.state") to buildingStatus(player, city, building))
            if (building.type.isResidence) {
                add(tr(player, "label.beds") to text.literal(CivsPopulation.houseBeds(building)))
                add(tr(player, "label.population") to text.literal(residents.size))
            }
            if (building.type != BuildingType.HOUSE && CivsRules.slots(city, building) > 0) add(tr(player, "label.workers") to tr(player, "common.fraction", "value" to assigned, "limit" to CivsRules.slots(city, building)))
            if (building.type == BuildingType.SAWMILL) add(tr(player, "label.output") to tr(player, "carpentry.automatic"))
            if (acceptedToolOrders.isNotEmpty()) add(tr(player, "label.output") to Component.join(text.literal(" · "),
                acceptedToolOrders.map { it.material }.distinct().map { materialName(player, it) }))
            CivsProduction.material(building)?.takeIf { acceptedToolOrders.isEmpty() && !CivsRules.isBuilder(city, building) && building.type != BuildingType.SAWMILL }?.let { material ->
                if (building.type != BuildingType.QUARRY) add(tr(player, if (building.type == BuildingType.FORESTER)
                    "forestry.species-label" else "label.output") to
                    if (building.type == BuildingType.FORESTER) treeSpecies(player, material)
                    else if (building.type == BuildingType.FARM) materialName(player, material)
                    else tr(player, "buildings.output", "amount" to CivsProduction.amount(building), "resource" to materialName(player, material)))
            }
            city.construction?.takeIf { it.buildingId == buildingId }?.let { order ->
                add(tr(player, "label.construction") to tr(player, "common.fraction", "value" to order.workDone, "limit" to order.workRequired))
            }
        }, if (building.type.isResidence) DialogTables.PanelSkin.COCOA else DialogTables.PanelSkin.NAVY),
        if (building.type.isResidence) residentRoster(player, residents) else null,
        if (building.type != BuildingType.DECORATION) body(player, "buildings.control-help") else null,
        if (canUpgrade(city, building)) upgradeSummary(player, city, building) else null,
        CivsResearch.required(building.type, building.level + 1)?.takeIf { building.level < host.maximumBuildingLevel(building) && !CivsResearch.has(city, it) }
            ?.let { body(tr(player, "research.upgrade-required", "name" to researchName(player, it), "level" to building.level + 1)) },
        if (legacyControl) body(player, "control.legacy-help") else null,
        if (legacyControl && city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
        request?.let { body(tint(if (requestTool != null) tr(player, "workers.tool-request-$requestTool")
            else tr(player, "buildings.request", "item" to materialName(player, it.material.name), "amount" to it.amount), STOCK)) },
        error?.let(::body),
    ) + if (building.type == BuildingType.TOWN_HALL) townHallBodies(player, city) else emptyList(), buildList {
        if (building.type != BuildingType.HOUSE && assigned > 0 && !busy) add(button(player, "summon", "buildings.summon", NAVIGATION) {
            perform(player, "civs.building", "buildings.detail-title", { host.summonWorker(player, buildingId) },
                success = { showBuilding(player, buildingId) }, failure = { showBuilding(player, buildingId, it) })
        }.copy(tooltip = tr(player, "buildings.summon-help")))
        if (request != null) {
            add(requestButton(player, request, "buildings.request-unavailable", "buildings.give-help", give = {
                perform(player, "civs.building", "buildings.detail-title",
                    { host.giveBuildingRequest(player, buildingId, request.material.name) },
                    success = { showBuilding(player, buildingId) }, failure = { showBuilding(player, buildingId, it) },
                    workingKey = null)
            }, refresh = { showBuilding(player, buildingId) }))
        }
        if (building.type != BuildingType.DECORATION && (city.inventory?.mode != CivStorageMode.PHYSICAL || !host.cityBusy(player))) {
            add(button(player, "warehouse", "warehouse.open", STOCK) { openWarehouse(player, buildingId) })
        }
        if (CivsRules.isBuilder(city, building) && city.construction != null) {
            add(button(player, "materials", "materials.open", COMMIT) { loadBuilderMaterials(player, buildingId) })
        } else if (building.phase == BuildingPhase.SITE || city.construction?.buildingId == buildingId || queued) {
            add(button(player, "materials", "materials.open", COMMIT) { loadBuildingMaterials(player, buildingId) })
        }
        if (queued || CivsRules.isBuilder(city, building) && city.constructionQueue.orEmpty().isNotEmpty())
            add(button(player, "queue", "queue.open", LAND) { showQueue(player) })
        if (building.type != BuildingType.HOUSE && CivsRules.slots(city, building) > 0) add(button(player, "workers", "buildings.workers", PEOPLE) { showBuildingWorkers(player, buildingId) })
        if (building.type == BuildingType.UNIVERSITY || CivsResearch.required(building.type, building.level + 1) != null)
            add(button(player, "research", "research.open", PERSONAL) { showResearch(player, city.id) })
        if (building.type == BuildingType.WAREHOUSE && city.inventory != null)
            add(button(player, "logistics", "logistics.open", STOCK) { showSupplyRequests(player, buildingId) })
        if ((building.type == BuildingType.SAWMILL || CivsProduction.options(building.type).isNotEmpty()) &&
            !CivsRules.isBuilder(city, building) && building.type != BuildingType.QUARRY)
            add(button(player, "production", when (building.type) {
                BuildingType.FORESTER -> "forestry.open"
                BuildingType.SAWMILL -> "carpentry.open"
                else -> "production.open"
            }, if (building.type == BuildingType.FORESTER) LAND else PERSONAL) {
                showProduction(player, buildingId)
            })
        if (building.type == BuildingType.BLACKSMITH)
            add(button(player, "tool_orders", "blacksmith.open", STOCK) { showCarpentry(player, buildingId) })
        if (canUpgrade(city, building)) add(button(player, "upgrade", "upgrades.start", COMMIT, appearance = Action.UPGRADE) {
            perform(player, "civs.building", "buildings.detail-title", { host.upgradeBuilding(player, buildingId) },
                success = { showBuilding(player, buildingId) }, failure = { showBuilding(player, buildingId, it) },
                workingKey = "upgrades.starting")
        })
        if (legacyControl && !host.cityBusy(player) && city.construction?.buildingId != buildingId) {
            add(button(player, "relocate_control", "control.open", NAVIGATION) { loadControlRelocation(player, buildingId) })
        }
        if (building.type != BuildingType.TOWN_HALL && !host.cityBusy(player)) {
            add(button(player, "delete_building", "delete-building.open", DANGER) { showDeleteBuilding(player, city.id, buildingId) })
        }
    }, reopen = { showBuilding(player, buildingId) }, cityId = city.id)
}


internal fun CivsUi.showDeleteBuilding(player: Player, expectedCityId: UUID, buildingId: UUID, error: Component? = null) {
    val city = host.city(player)
    if (city == null || city.id != expectedCityId || !city.canManage(player.uniqueId)) {
        notice(player, "civs.delete-building", "delete-building.title", text.reason(player, "stale-city")) { showEntry(player) }
        return
    }
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (building.type == BuildingType.TOWN_HALL) return showBuilding(player, buildingId)
    present(player, "civs.delete-building", "delete-building.title", listOfNotNull(
        body(tint(buildingName(player, building.type, building.level), DANGER)),
        body(player, "delete-building.world-help"), body(player, "delete-building.control-help"),
        if (city.construction?.buildingId == buildingId) body(player, "delete-building.construction-help") else null,
        if (city.inventory?.buildingStock(buildingId)?.values.orEmpty().any { it > 0 }) body(player, "delete-building.virtual-help") else null,
        busyBody(player), error?.let { body(it.color(DANGER)) },
    ), buildList {
        if (!host.cityBusy(player)) add(button(player, "delete", "delete-building.confirm", DANGER, appearance = Action.DELETE) {
            perform(player, "civs.delete-building", "delete-building.title",
                { host.deleteBuilding(player, expectedCityId, buildingId) }, success = {
                    runtime.beginFlow(player)
                    roots[player.uniqueId] = "civs.buildings"
                    showBuildings(player)
                    player.sendMessage(text.success(player, "delete-building.success", "building" to text.building(player, building.type)))
                }, failure = { showDeleteBuilding(player, expectedCityId, buildingId, it) })
        })
    }, reopen = { showDeleteBuilding(player, expectedCityId, buildingId) }, cityId = city.id)
}


internal fun CivsUi.loadControlRelocation(player: Player, buildingId: UUID, error: Component? = null) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (building.phase != BuildingPhase.ACTIVE) return showBuilding(player, buildingId, text.reason(player, "control-relocation-site"))
    val visit = present(player, "civs.control-relocation", "control.title", listOf(body(player, "control.loading")), emptyList(), cityId = city.id) ?: return
    val future = try { host.buildingControl(player, buildingId) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
    future.whenCompleteSync(tasks, visit.epoch) { target, failure ->
        if (!current(player, visit)) return@whenCompleteSync
        if (host.city(player)?.id != city.id) showEntry(player)
        else if (failure != null) showBuilding(player, buildingId, text.failure(player, failure).color(DANGER))
        else showControlRelocation(player, buildingId, requireNotNull(target), error)
    }
}


internal fun CivsUi.showControlRelocation(player: Player, buildingId: UUID, target: CivPosition, error: Component? = null) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (building.phase != BuildingPhase.ACTIVE || building.control == target) return showBuilding(player, buildingId, error)
    val busy = host.cityBusy(player)
    present(player, "civs.control-relocation", "control.title", listOfNotNull(
        body(buildingName(player, building.type, building.level)),
        facts(player, listOf(
            tr(player, "control.old-position") to (building.control?.let { coordinates(player, it) } ?: tr(player, "control.unset")),
            tr(player, "control.new-position") to coordinates(player, target),
        )), body(player, "control.help"), body(player, "control.retained-help"),
        if (city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
        busyBody(player), error?.let { body(it.color(DANGER)) },
    ), buildList {
        if (!busy && city.construction?.buildingId != buildingId) add(button(player, "relocate", "control.confirm", COMMIT, appearance = Action.CONFIRM) {
            perform(player, "civs.control-relocation", "control.title", { host.relocateControl(player, buildingId, target) },
                success = { showBuilding(player, buildingId, tr(player, "control.success").color(COMMIT)) },
                failure = { loadControlRelocation(player, buildingId, it) })
        })
    }, reopen = { loadControlRelocation(player, buildingId) }, cityId = city.id)
}


internal fun CivsUi.showBuildingWorkers(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    val slots = CivsRules.slots(city, building)
    val assigned = city.workers.count { it.assignment == buildingId }
    val workers = city.workers.filter { !CivsResidents.isChild(it) && (assigned < slots || it.assignment == buildingId) }
        .sortedBy { it.assignment != buildingId }
    val current = page.coerceIn(0, lastPage(workers.size))
    present(player, "civs.building-workers", "workers.building-title", listOfNotNull(
        body(buildingName(player, building.type, building.level)),
        body(detail(player, "label.workers", tr(player, "common.fraction", "value" to assigned, "limit" to slots))),
        body(player, "workers.building-help"),
        if (workers.isEmpty()) body(player, "workers.arrival-help") else null,
        if (assigned >= slots) body(player, "reason.workplace-full") else null,
        busyBody(player), error?.let(::body),
    ), buildList {
        if (!host.cityBusy(player)) workers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, worker ->
            val selected = worker.assignment == buildingId
            if (selected || assigned < slots) add(valueButton(player, "worker_$index", if (selected) "workers.remove-choice" else "common.choice",
                text.literal(worker.name), if (selected) COMMIT else PEOPLE,
                worker.assignment?.let(city::building)?.let { buildingName(player, it.type, it.level) } ?: workerStatus(player, worker)) {
                perform(player, "civs.building-workers", "workers.building-title", { host.assign(player, worker.id, if (selected) null else buildingId) },
                    success = { showBuildingWorkers(player, buildingId, current) }, failure = { showBuildingWorkers(player, buildingId, current, it) })
            })
        }
        pages(player, current, workers.size) { showBuildingWorkers(player, buildingId, it) }
    }, reopen = { showBuildingWorkers(player, buildingId, current) }, cityId = city.id)
}


internal fun CivsUi.showProduction(player: Player, buildingId: UUID, error: Component? = null) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (CivsRules.isBuilder(city, building)) return showBuilding(player, buildingId)
    if (building.type == BuildingType.FORESTER) return showForesterSpecies(player, city.id, buildingId, error)
    if (building.type == BuildingType.SAWMILL) return showCarpentry(player, buildingId)
    if (building.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER)) return showIndustryBuilding(player, city.id, buildingId, error)
    val options = CivsProduction.options(city, building)
    val selected = CivsProduction.material(building)
    val inputs = CivsProduction.inputs(building)
    val available = if (inputs.isNotEmpty()) runCatching { host.localMaterials(player, buildingId) } else null
    val locked = host.cityBusy(player) || city.construction?.buildingId == buildingId
    val toolOrders = if (building.type == BuildingType.BLACKSMITH) runCatching { host.carpentry(player, buildingId) }
        .getOrNull()?.orders.orEmpty() else emptyList()
    present(player, "civs.production", "production.title", listOfNotNull(
        body(buildingName(player, building.type, building.level)), if (building.type != BuildingType.BLACKSMITH) body(player,
            when { building.type == BuildingType.FARM && inputs.isEmpty() -> "production.field-help"
                building.type == BuildingType.FORESTER -> "production.forest-help"
                inputs.isEmpty() -> "production.help"
                building.type == BuildingType.TOWN_HALL -> "production.scribe-help"
                else -> "production.processor-help" }) else null,
        if (toolOrders.isNotEmpty()) body(tr(player, "blacksmith.active-orders", "items" to
            Component.join(text.literal(" · "), toolOrders.map { order -> tr(player, "buildings.output",
                "resource" to materialName(player, order.material), "amount" to order.amount) }))) else null,
        if (building.type == BuildingType.BLACKSMITH) body(player, "blacksmith.manual-help") else null,
        if (inputs.isNotEmpty()) table(inputs.map { (material, needed) -> materialName(player, material) to
            tr(player, "common.required", "have" to if (available?.isSuccess == true)
                text.literal(available.getOrNull()?.get(material) ?: 0) else tr(player, "workers.stock-unknown"), "need" to needed) }) else null,
        if (inputs.isNotEmpty()) body(tr(player, "production.recipe-output", "item" to materialName(player, requireNotNull(selected)),
            "amount" to CivsProduction.amount(building))) else null,
        available?.exceptionOrNull()?.let { body(text.failure(player, it)) },
        if (building.type == BuildingType.BLACKSMITH && !CivsResearch.has(city, CivResearchId.ENGINEERING)) body(player, "industry.forge-gates") else null,
        if (city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
        busyBody(player), error?.let(::body),
    ), buildList {
        if (building.type == BuildingType.BLACKSMITH)
            add(button(player, "tool_orders", "blacksmith.open", STOCK) { showCarpentry(player, buildingId) })
        if (building.type == BuildingType.BLACKSMITH && !CivsResearch.has(city, CivResearchId.ENGINEERING))
            add(button(player, "research", "research.open", PERSONAL) { showResearchDetail(player, city.id, CivResearchId.ENGINEERING) })
        if (!locked && (inputs.isEmpty() || options.size > 1)) options.forEachIndexed { index, material ->
            val recipe = building.copy(production = material)
            val recipeInputs = CivsProduction.inputs(recipe)
            val tooltip = if (recipeInputs.isEmpty()) Component.empty() else tr(player, "production.recipe-tooltip",
                "inputs" to Component.join(text.literal(", "), recipeInputs.map { (key, amount) ->
                    materialName(player, key).append(text.literal(" × $amount")) }))
            add(valueButton(player, "production_$index", if (material == selected) "common.selected" else "common.choice",
                if (building.type in setOf(BuildingType.FARM, BuildingType.FORESTER) && recipeInputs.isEmpty()) materialName(player, material)
                else tr(player, "buildings.output", "resource" to materialName(player, material), "amount" to CivsProduction.amount(recipe)),
                if (material == selected) COMMIT else WHITE, tooltip) {
                if (material == selected) showProduction(player, buildingId)
                else perform(player, "civs.production", "production.title", { host.setProduction(player, buildingId, material) },
                    success = { showProduction(player, buildingId) }, failure = { showProduction(player, buildingId, it) })
            })
        }
    }, reopen = { showProduction(player, buildingId) }, cityId = city.id)
}


internal fun CivsUi.treeSpecies(player: Player, material: String): Component =
    tr(player, "forestry.species.${material.removeSuffix("_LOG").lowercase(Locale.ROOT)}")


internal fun CivsUi.showForesterSpecies(player: Player, cityId: UUID, buildingId: UUID, error: Component? = null) {
    val city = selectedCity(player, cityId) ?: return
    val building = city.building(buildingId)?.takeIf { it.type == BuildingType.FORESTER } ?: return
    val selected = requireNotNull(CivsProduction.material(building))
    val canChoose = canManageSelected(player, city)
    val locked = (city.autonomy == null && host.cityBusy(player)) || city.construction?.buildingId == buildingId
    present(player, "civs.forester-species", "forestry.title", listOfNotNull(
        body(buildingName(player, building.type, building.level)),
        body(tr(player, "common.detail", "label" to tr(player, "forestry.species-label"),
            "value" to treeSpecies(player, selected).color(COMMIT))),
        body(player, if (city.autonomy == null) "forestry.native-help" else "forestry.auto-help"),
        if (city.forestryJobs?.get(buildingId.toString())?.timber?.let { it != selected } == true)
            body(player, "forestry.finishing-tree") else null,
        if (!canChoose) body(text.reason(player, "not-authorized")) else null,
        if (locked) body(text.reason(player, if (city.construction?.buildingId == buildingId) "construction-busy" else "city-busy")) else null,
        error?.let(::body),
    ), buildList {
        if (canChoose && !locked) CivsProduction.options(city, building).forEachIndexed { index, material ->
            add(valueButton(player, "production_$index", if (material == selected) "common.selected" else "common.choice",
                treeSpecies(player, material), if (material == selected) COMMIT else WHITE,
                tr(player, "forestry.saplings", "item" to materialName(player, material.removeSuffix("_LOG") + "_SAPLING"),
                    "amount" to if (CivsForestry.large(material)) 4 else 1)) {
                if (material == selected) showForesterSpecies(player, cityId, buildingId)
                else perform(player, "civs.forester-species", "forestry.title", {
                    if (city.autonomy == null) host.setProduction(player, buildingId, material)
                    else host.setForesterSpecies(player, cityId, buildingId, material)
                }, success = { showForesterSpecies(player, cityId, buildingId) },
                    failure = { showForesterSpecies(player, cityId, buildingId, it) })
            })
        }
    }, reopen = { showForesterSpecies(player, cityId, buildingId) }, cityId = city.id)
}


internal fun CivsUi.showCarpentry(player: Player, buildingId: UUID, page: Int = 0) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    if (building.type !in setOf(BuildingType.SAWMILL, BuildingType.BLACKSMITH)) return showBuilding(player, buildingId)
    val forge = building.type == BuildingType.BLACKSMITH
    val result = runCatching { host.carpentry(player, buildingId) }
    val view = result.getOrNull()
    val orders = view?.orders.orEmpty()
    val current = page.coerceIn(0, lastPage(orders.size, CARPENTRY_PAGE_SIZE))
    present(player, "civs.production", if (forge) "blacksmith.title" else "carpentry.title", buildList {
        add(body(buildingName(player, building.type, building.level)))
        add(body(player, if (forge) "blacksmith.help" else "carpentry.help"))
        result.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
        if (view == null) add(body(player, "carpentry.unavailable"))
        else if (view.paused) add(body(tr(player, "carpentry.paused").color(STOCK)))
        else if (orders.isEmpty()) add(body(player, if (view.planning) "carpentry.planning" else if (forge) "blacksmith.waiting" else "carpentry.waiting"))
        orders.drop(current * CARPENTRY_PAGE_SIZE).take(CARPENTRY_PAGE_SIZE).forEach { order ->
            val status = if (view?.paused == true) "order-paused"
                else if (order.status == "waiting-materials") "waiting-materials" else "crafting"
            add(card(listOfNotNull(
                tr(player, "buildings.output", "resource" to materialName(player, order.material), "amount" to order.amount).color(STOCK),
                tr(player, "carpentry.worker-status", "worker" to text.literal(order.workerName).color(PEOPLE),
                    "status" to tr(player, "carpentry.$status")),
                tr(player, "carpentry.target", "target" to (order.targetBuildingId?.let(city::building)?.let {
                    buildingName(player, it.type, it.level) } ?: tr(player, "carpentry.storage"))),
                if (order.demandMaterial != order.material) tr(player, "carpentry.demand", "item" to materialName(player, order.demandMaterial)) else null,
                tr(player, if (order.missing.isEmpty()) "carpentry.inputs" else "carpentry.missing",
                    "items" to materialAmounts(player, if (order.missing.isEmpty()) order.inputs else order.missing))
                    .color(if (order.missing.isEmpty()) CivsText.BODY else STOCK),
            ), 360))
        }
    }, buildList {
        if (view != null) add(button(player, "recipes", "carpentry.recipes-open", PERSONAL) {
            showCarpentryRecipes(player, buildingId, view.level.coerceIn(1, 3))
        })
        pages(player, current, orders.size, CARPENTRY_PAGE_SIZE) { showCarpentry(player, buildingId, it) }
    }, reopen = { showCarpentry(player, buildingId, current) }, cityId = city.id)
}


internal fun CivsUi.showCarpentryRecipes(player: Player, buildingId: UUID, tier: Int, page: Int = 0) {
    val city = city(player) ?: return
    val building = city.building(buildingId) ?: return showBuildings(player)
    val view = runCatching { host.carpentry(player, buildingId) }.getOrNull() ?: return showCarpentry(player, buildingId)
    val recipes = view.recipes.filter { it.level == tier }
    val forge = building.type == BuildingType.BLACKSMITH
    val current = page.coerceIn(0, lastPage(recipes.size, CARPENTRY_PAGE_SIZE))
    present(player, "civs.carpentry-recipes", if (forge) "blacksmith.recipes-title" else "carpentry.recipes-title", buildList {
        add(body(buildingName(player, building.type, building.level)))
        add(body(tr(player, "carpentry.tier", "level" to tier).color(PERSONAL)))
        add(body(player, if (forge) "blacksmith.recipes-help" else "carpentry.recipes-help"))
        if (tier > view.level) add(body(tr(player, "carpentry.locked", "level" to tier).color(STOCK)))
        if (recipes.isEmpty()) add(body(player, "carpentry.no-recipes"))
        recipes.drop(current * CARPENTRY_PAGE_SIZE).take(CARPENTRY_PAGE_SIZE).forEach { recipe ->
            add(card(listOfNotNull(
                tr(player, "buildings.output", "resource" to materialName(player, recipe.material), "amount" to recipe.amount).color(STOCK),
                tr(player, "carpentry.inputs", "items" to materialAmounts(player, recipe.inputs)),
                if (recipe.stripping) tr(player, "carpentry.stripping") else null,
            ), 360))
        }
    }, buildList {
        for (level in 1..3) add(valueButton(player, "tier_$level", when {
            level == tier -> "common.selected"
            level > view.level -> "carpentry.locked-choice"
            else -> "common.choice"
        }, tr(player, "carpentry.tier", "level" to level), if (level == tier) COMMIT else WHITE,
            if (level > view.level) tr(player, "carpentry.locked", "level" to level) else Component.empty()) {
                showCarpentryRecipes(player, buildingId, level)
            })
        pages(player, current, recipes.size, CARPENTRY_PAGE_SIZE) { showCarpentryRecipes(player, buildingId, tier, it) }
    }, reopen = { showCarpentryRecipes(player, buildingId, tier, current) }, cityId = city.id)
}


internal fun CivsUi.materialAmounts(player: Player, materials: Map<String, Long>): Component =
    Component.join(text.literal(", "), materials.map { (material, amount) ->
        materialName(player, material).append(text.literal(" × $amount"))
    })


internal fun CivsUi.queuePosition(city: Settlement, buildingId: UUID): Int? =
    city.constructionQueue.orEmpty().indexOf(buildingId).takeIf { it >= 0 }?.plus(1)


internal fun CivsUi.buildingStatus(player: Player, city: Settlement, building: CivBuilding): Component {
    queuePosition(city, building.id)?.let { return tint(tr(player, "building-status.queued", "position" to it), STOCK) }
    val key = "building-status.${host.buildingStatus(building.id).removePrefix("building-status.")}"
    return tint(tr(player, key.takeIf { it in text.keys("ru") } ?: "workers.status.unavailable",
        "position" to queuePosition(city, building.id)), statusColor(key))
}


internal fun CivsUi.buildingSummaryStatus(player: Player, city: Settlement, building: CivBuilding): Component {
    queuePosition(city, building.id)?.let { return tint(tr(player, "building-status.queued", "position" to it), STOCK) }
    val order = city.construction?.takeIf { it.buildingId == building.id }
    if (order != null) {
        val state = tint(tr(player, if (order.targetLevel > building.level) "buildings.upgrading" else "buildings.constructing",
            "level" to order.targetLevel), if (order.targetLevel > building.level) PERSONAL else STOCK)
        return if (order.phase in setOf(ConstructionPhase.WAITING_MATERIALS, ConstructionPhase.PAUSED))
            state.append(Component.newline()).append(buildingStatus(player, city, building)) else state
    }
    return if (building.phase == BuildingPhase.ACTIVE) tint(tr(player, "building-status.active"), COMMIT)
        else tint(tr(player, "building-phase.${building.phase.name.lowercase(Locale.ROOT)}"), STOCK)
}


internal fun CivsUi.canUpgrade(city: Settlement, building: CivBuilding): Boolean =
    building.level < host.maximumBuildingLevel(building) && building.phase == BuildingPhase.ACTIVE && queuePosition(city, building.id) == null &&
        city.construction?.buildingId != building.id && (building.type == BuildingType.TOWN_HALL || building.level < city.level()) &&
        CivsResearch.required(building.type, building.level + 1).let { it == null || CivsResearch.has(city, it) }


internal fun CivsUi.upgradeSummary(player: Player, city: Settlement, building: CivBuilding): PaperDialogBody = presentation.paragraph(Component.join(Component.newline(), buildList {
    val target = building.level + 1
    add(tint(detail(player, "upgrades.next-level", buildingName(player, building.type, target)), PERSONAL))
    add(upgradeBenefit(player, building))
    val slotsBefore = CivsRules.slots(city, building)
    val slotsAfter = CivsRules.slots(city, building.copy(level = target))
    if (slotsAfter > 0) add(detail(player, "label.workers", tr(player, "common.change", "before" to slotsBefore, "after" to slotsAfter)))
    if (city.inventory?.mode == CivStorageMode.VIRTUAL) add(detail(player, "warehouse.capacity", tr(player, "common.change",
        "before" to CivInventory.buildingCapacity(building), "after" to CivInventory.buildingCapacity(building.copy(level = target)))))
    if (building.type == BuildingType.TOWN_HALL) add(detail(player, "label.radius", tr(player, "common.change", "before" to city.radius, "after" to city.radius.toLong() + 32)))
}))


internal fun CivsUi.materialDeficit(required: Map<String, Long>, available: Map<String, Long>): Map<String, Long> =
    required.mapValues { (material, count) -> (count - (available[material] ?: 0L)).coerceAtLeast(0L) }.filterValues { it > 0L }


internal fun CivsUi.upgradeBenefit(player: Player, building: CivBuilding): Component = when (building.type) {
    BuildingType.TOWN_HALL -> tr(player, "upgrades.population", "before" to building.level * 4, "after" to (building.level + 1) * 4)
    BuildingType.HOUSE -> tr(player, "upgrades.beds", "before" to CivsPopulation.houseBeds(building),
        "after" to CivsPopulation.houseBeds(building.copy(level = building.level + 1)))
    BuildingType.TAVERN -> tr(player, "upgrades.tavern")
    BuildingType.GUARD_TOWER -> tr(player, "upgrades.guard-tower")
    BuildingType.WAREHOUSE -> tr(player, "upgrades.warehouse")
    BuildingType.BUILDER_HUT -> tr(player, "upgrades.builder")
    BuildingType.FARM -> tr(player, "upgrades.field-worker")
    BuildingType.FORESTER -> tr(player, "upgrades.forest-worker")
    BuildingType.QUARRY -> tr(player, "upgrades.mining", "before" to building.level, "after" to building.level + 1)
    BuildingType.SAWMILL -> tr(player, "upgrades.carpentry", "level" to building.level + 1)
    BuildingType.UNIVERSITY -> tr(player, "research.university-upgrade")
    BuildingType.SMELTERY, BuildingType.CRUSHER -> tr(player, "industry.processor-upgrade")
    BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY, BuildingType.BAKERY -> tr(player, "upgrades.processor")
    else -> tr(player, "upgrades.production", "before" to CivsProduction.amount(building),
        "after" to CivsProduction.amount(building.copy(level = building.level + 1)))
}


internal fun CivsUi.constructionText(player: Player, city: Settlement): Component {
    val order = city.construction ?: return tr(player, "city.no-construction")
    val planned = order.plannedBuilding ?: city.building(order.buildingId)
    return tr(player, "city.construction", "building" to (planned?.let { text.building(player, it.type) } ?: tr(player, "buildings.generic")),
        "done" to order.workDone, "total" to order.workRequired,
        "state" to (planned?.let { buildingStatus(player, city, it) }
            ?: tr(player, "construction-phase.${order.phase.name.lowercase(Locale.ROOT)}")))
}
