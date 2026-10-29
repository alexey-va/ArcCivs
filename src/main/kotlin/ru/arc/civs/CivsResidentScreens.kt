package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogDecoration
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogBody
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.SKILLS_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.WHITE
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.PEOPLE
import ru.arc.civs.CivsUi.Companion.STOCK
import ru.arc.civs.CivsUi.Companion.NAVIGATION
import ru.arc.civs.CivsUi.Companion.DANGER

internal fun CivsUi.residentRoster(player: Player, residents: List<CivWorker>, width: Int = 320): PaperDialogBody =
    if (residents.isEmpty()) body(player, "buildings.no-residents") else presentation.paragraph(Component.join(Component.newline(),
        listOf(tint(tr(player, "buildings.residents"), PEOPLE)) + residents.map { resident ->
            tr(player, "common.detail", "label" to tint(text.literal(resident.name), PEOPLE),
                "value" to tr(player, if (CivsResidents.isChild(resident)) "workers.child" else "workers.adult"))
        }), width)


internal fun CivsUi.showWorkers(player: Player, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val current = page.coerceIn(0, lastPage(city.workers.size))
    val limit = minOf(CivsRules.populationLimit(city), CivsRules.beds(city))
    present(player, "civs.workers", "workers.title", listOfNotNull(
        body(player, "workers.help"), presentation.summary(Component.join(Component.newline(), listOf(
            detail(player, "label.population", tint(tr(player, "common.fraction", "value" to city.workers.size, "limit" to limit), PEOPLE)),
            detail(player, "label.unassigned", tint(text.literal(city.workers.count { it.assignment == null && !CivsResidents.isChild(it) }), STOCK)),
        ))), body(player, "guide.population-help"),
        busyBody(player), error?.let(::body),
    ), buildList {
        city.workers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, worker ->
            add(valueButton(player, "worker_$index", "common.destination", text.literal(worker.name), PEOPLE,
                workerStatus(player, worker)) { showWorker(player, worker.id) })
        }
        pages(player, current, city.workers.size) { showWorkers(player, it) }
    }, reopen = { showWorkers(player, current) }, cityId = city.id)
}


internal fun CivsUi.showWorker(player: Player, workerId: UUID, error: Component? = null, cityId: UUID? = null) {
    val city = residentCity(player, workerId, cityId) ?: return
    val worker = city.workers.firstOrNull { it.id == workerId }
        ?: return if (cityId == null) showWorkers(player) else Unit
    val building = worker.assignment?.let(city::building)
    val child = CivsResidents.isChild(worker)
    val needs = CivsResidentLife.needs(city, worker)
    val home = worker.homeId?.let(city::building)?.takeIf {
        it.phase == BuildingPhase.ACTIVE && (it.type == BuildingType.TOWN_HALL || it.type.isResidence)
    }
    val personal = city.autonomy == null
    val request = if (personal) host.workerRequest(player, workerId) else null
    val toolKind = requestTool(request)
    present(player, "civs.worker", "workers.detail-title", listOfNotNull(
        body(tint(text.literal(worker.name), PEOPLE)),
        body(text.residentVitals(player, CivsResidents.health(worker), CivsResidents.maxHealth(worker), CivsResidents.hunger(worker))),
        presentation.facts(listOfNotNull(
            tr(player, "label.profession") to tr(player, "profession.${if (child) "child"
                else building?.let { CivsRules.profession(city, it) } ?: "unassigned"}").color(PEOPLE),
            building?.let { tr(player, "label.workplace") to buildingName(player, it.type, it.level) },
            tr(player, "label.home") to
                (home?.let { buildingName(player, it.type, it.level) }
                    ?: tr(player, "workers.home-unassigned")),
            tr(player, "label.age") to tr(player, if (child) "workers.child" else "workers.adult"),
            tr(player, "workers.aptitude") to residentAptitude(player, worker),
            tr(player, "workers.learning-cap") to tr(player, "workers.experience", "xp" to residentLearningCap(city, worker)),
            if (child) tr(player, "workers.education") to tr(player, "workers.education-progress",
                "xp" to worker.life?.education.orEmpty().values.sum(), "max" to CivsResidentLife.MAX_EDUCATION_XP) else null,
            tr(player, "label.state") to DialogDecoration.stateBadge(workerStatus(player, worker), DialogDecoration.StateTone.INFO),
            tr(player, "workers.happiness") to residentMood(player, needs.happiness),
        ), DialogTables.PanelSkin.PLUM),
        residentNeeds(player, needs),
        request?.let { body(tint(if (toolKind != null) tr(player, "workers.tool-request-$toolKind")
            else tr(player, "buildings.request", "item" to materialName(player, it.material.name), "amount" to it.amount), STOCK)) },
        worker.cargo?.let { cargo -> body(tr(player, "logistics.cargo", "item" to
            (cargo.stockResource?.let { text.resource(player, it) } ?: materialName(player, cargo.material)),
            "amount" to cargo.amount, "target" to (CivsLogistics.destination(city, cargo)?.let {
                buildingName(player, it.type, it.level) } ?: tr(player, "buildings.generic")))) },
        if (worker.cargo != null) body(player, "logistics.cargo-help") else null,
        worker.pendingStock?.takeUnless { it == Stock() }?.let { body(tr(player, "logistics.pending-stock",
            "goods" to Component.join(text.literal(" · "), Resource.entries.filter { resource -> it[resource] > 0 }.map { resource ->
                tr(player, "logistics.stock-count", "item" to text.resource(player, resource), "amount" to it[resource])
            }))) },
        if (personal) busyBody(player) else null, error?.let(::body),
    ), buildList {
        if (personal && building != null && CivsRules.isBuilder(city, building) && city.construction != null)
            add(button(player, "materials", "materials.open", COMMIT) { loadBuilderMaterials(player, building.id, workerId) })
        if (personal && building?.type == BuildingType.WAREHOUSE)
            add(button(player, "logistics", "logistics.open", STOCK) { showSupplyRequests(player, building.id) })
        if (request != null) add(requestButton(player, request, "workers.supply-unavailable", "workers.supply-help", give = {
            perform(player, "civs.worker", "workers.detail-title", { host.giveWorkerRequest(player, workerId, request.material.name) },
                success = { showWorker(player, workerId, cityId = cityId) }, failure = { showWorker(player, workerId, it, cityId) },
                workingKey = null)
        }, refresh = { showWorker(player, workerId, cityId = cityId) }))
        add(button(player, "inventory", "workers.inventory-open", STOCK) { showWorkerInventory(player, workerId, cityId) })
        add(button(player, "skills", "workers.skills-open", PERSONAL) { showWorkerSkills(player, workerId, cityId = cityId) })
        if (personal && !host.cityBusy(player) && CivsEquipment.plainCount(worker, "BREAD") > 0 &&
            CivsResidents.health(worker) < CivsResidents.maxHealth(worker)) {
            add(button(player, "feed", "workers.feed", COMMIT, appearance = Action.CONFIRM) {
                perform(player, "civs.worker", "workers.detail-title", { host.feedWorker(player, workerId) },
                    success = { showWorker(player, workerId, cityId = cityId) }, failure = { showWorker(player, workerId, it, cityId) })
            }.copy(tooltip = tr(player, "workers.feed-help")))
        }
        if (personal && !CivsResidents.isChild(worker) && !host.cityBusy(player)) add(button(player, "assign", "workers.assign", PERSONAL) { showAssignments(player, workerId) })
        if (personal && worker.assignment != null && !host.cityBusy(player)) add(button(player, "unassign", "workers.unassign") {
            perform(player, "civs.worker", "workers.detail-title", { host.assign(player, workerId, null) },
                success = { showWorker(player, workerId, cityId = cityId) }, failure = { showWorker(player, workerId, it, cityId) })
        })
    }, reopen = { showWorker(player, workerId, cityId = cityId) }, cityId = city.id)
}


internal fun CivsUi.residentAptitude(player: Player, worker: CivWorker): Component =
    tr(player, "profession.${CivsRules.profession(CivsResidentLife.aptitude(worker))}").color(PERSONAL)

/** Legacy receipts retain their original training cap until observed life state exists. */

internal fun CivsUi.residentLearningCap(city: Settlement, worker: CivWorker): Long =
    if (worker.life == null) 40L else CivsResidentLife.learningCap(city, worker)


internal fun CivsUi.residentMood(player: Player, happiness: Int): Component = when {
    happiness < 50 -> tint(tr(player, "workers.mood.sad"), DANGER)
    happiness < 70 -> tint(tr(player, "workers.mood.calm"), NAVIGATION)
    else -> tint(tr(player, "workers.mood.happy"), COMMIT)
}


internal fun CivsUi.residentNeeds(player: Player, needs: CivResidentNeeds): PaperDialogBody {
    val (positive, negative) = needs.causes.partition { it in setOf(CivResidentNeed.FED, CivResidentNeed.HOUSED,
        CivResidentNeed.RESTED, CivResidentNeed.OCCUPIED, CivResidentNeed.FAMILY, CivResidentNeed.GUARDED) }
    fun cause(value: CivResidentNeed): Component = tint(tr(player, "workers.need.${value.name.lowercase(Locale.ROOT)}"),
        if (value in positive) COMMIT else if (value in setOf(CivResidentNeed.HUNGRY, CivResidentNeed.HOMELESS)) DANGER else STOCK)
    return presentation.lists(listOf(tint(tr(player, "workers.needs-positive"), COMMIT) to positive.map(::cause),
        tint(tr(player, "workers.needs-negative"), STOCK) to negative.map(::cause)), DialogTables.PanelSkin.NAVY)
}


internal fun CivsUi.showWorkerSkills(player: Player, workerId: UUID, page: Int = 0, cityId: UUID? = null) {
    val city = residentCity(player, workerId, cityId) ?: return
    val worker = city.workers.firstOrNull { it.id == workerId }
        ?: return if (cityId == null) showWorkers(player) else Unit
    val professions = CivsResidents.professions.map(BuildingType::valueOf)
    val child = CivsResidents.isChild(worker)
    val cap = residentLearningCap(city, worker)
    val current = page.coerceIn(0, lastPage(professions.size, SKILLS_PAGE_SIZE))
    val introduction = presentation.compact(listOf(
        body(text.literal(worker.name)), body(player, if (child) "workers.child-learning-help"
            else if (worker.life != null) "workers.skills-life-help" else "workers.skills-help"),
        facts(player, listOfNotNull(
            tr(player, "workers.aptitude") to residentAptitude(player, worker),
            tr(player, "workers.learning-cap") to tr(player, "workers.experience", "xp" to cap),
            if (child) tr(player, "workers.education") to tr(player, "workers.education-progress",
                "xp" to worker.life?.education.orEmpty().values.sum(), "max" to CivsResidentLife.MAX_EDUCATION_XP) else null,
        )),
    ))
    present(player, "civs.worker-skills", "workers.skills-title", listOf(
        introduction,
        table(professions.drop(current * SKILLS_PAGE_SIZE).take(SKILLS_PAGE_SIZE).map { type ->
            val level = CivsResidents.skillLevel(worker, type.name)
            val experience = CivsResidents.skillExperience(worker, type.name)
            tr(player, "profession.${CivsRules.profession(type)}") to when {
                child -> tr(player, "workers.experience", "xp" to (worker.life?.education?.get(type.name) ?: 0L))
                level >= 5 -> tr(player, "workers.skill-max", "level" to level)
                experience >= cap -> tr(player, "workers.skill-home-cap", "level" to level, "xp" to experience)
                else -> tr(player, "workers.skill-progress", "level" to level, "xp" to experience, "goal" to minOf(cap, level * 10L))
            }
        }, frame = DialogTables.Frame.EPIC),
        presentation.compact(listOf(body(player, "workers.learning-cap-help"),
            body(tr(player, "workers.endurance-help", "health" to residentNumber(CivsResidents.maxHealth(worker)))))),
    ), buildList { pages(player, current, professions.size, SKILLS_PAGE_SIZE) { showWorkerSkills(player, workerId, it, cityId) } },
        reopen = { showWorkerSkills(player, workerId, current, cityId) }, cityId = city.id)
}


internal fun CivsUi.showWorkerInventory(player: Player, workerId: UUID, cityId: UUID? = null) {
    val city = residentCity(player, workerId, cityId) ?: return
    val worker = city.workers.firstOrNull { it.id == workerId }
        ?: return if (cityId == null) showWorkers(player) else Unit
    visits.remove(player.uniqueId)
    roots.remove(player.uniqueId)
    runtime.close(player)
    equipment.open(player, worker.name,
        items = { if (city.autonomy != null) host.residentItems(player, city.id, workerId) else host.residentItems(player, workerId) },
        transfer = { actor, npcSlot, playerSlot, click ->
            if (city.autonomy != null) CompletableFuture.failedFuture(CivsRejection("not-authorized"))
            else host.transferResidentItem(actor, workerId, npcSlot, playerSlot, click)
        },
        back = {
            runtime.beginFlow(player)
            roots[player.uniqueId] = "civs.worker"
            showWorker(player, workerId, cityId = cityId)
        }, stockDetails = {
            val latest = if (city.autonomy == null) null else runCatching {
                host.inspectResident(player, city.id, workerId).workers.firstOrNull { it.id == workerId }
            }.getOrNull()
            if (latest == null || city.autonomy == null) emptyList() else buildList {
                Resource.entries.filter { (latest.pendingStock?.get(it) ?: 0L) > 0 }.forEach { resource ->
                    add(tr(player, "logistics.pending-resource", "item" to text.resource(player, resource),
                        "amount" to requireNotNull(latest.pendingStock)[resource]))
                }
                latest.cargo?.stockResource?.let { resource ->
                    add(tr(player, "logistics.carried-resource", "item" to text.resource(player, resource),
                        "amount" to requireNotNull(latest.cargo).amount))
                }
            }
        }, readOnly = city.autonomy != null)
}


internal fun CivsUi.residentCity(player: Player, workerId: UUID, cityId: UUID?): Settlement? {
    if (cityId == null) return city(player)
    return try { host.inspectResident(player, cityId, workerId) } catch (failure: Exception) {
        present(player, "civs.worker", "workers.detail-title", listOf(body(text.failure(player, failure))),
            emptyList(), cityId = cityId)
        null
    }
}


internal fun CivsUi.residentNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')


internal fun CivsUi.showAssignments(player: Player, workerId: UUID, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
    val places = city.buildings.filter { building -> CivsRules.slots(city, building) > 0 &&
        (worker.assignment == building.id || city.workers.count { it.assignment == building.id } < CivsRules.slots(city, building)) }
    val current = page.coerceIn(0, lastPage(places.size))
    present(player, "civs.assign", "workers.assign-title", listOfNotNull(
        body(tr(player, if (places.isEmpty()) "workers.no-jobs" else "workers.assign-help", "name" to worker.name)), busyBody(player), error?.let(::body),
    ), buildList {
        if (!host.cityBusy(player)) places.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
            val selected = worker.assignment == building.id
            add(valueButton(player, "assign_$index", if (selected) "common.selected" else "common.choice",
                buildingName(player, building.type, building.level), if (selected) COMMIT else WHITE) {
                if (selected) showWorker(player, workerId)
                else perform(player, "civs.assign", "workers.assign-title", { host.assign(player, workerId, building.id) },
                    success = { showWorker(player, workerId) }, failure = { showAssignments(player, workerId, current, it) })
            })
        }
        pages(player, current, places.size) { showAssignments(player, workerId, it) }
    }, reopen = { showAssignments(player, workerId, current) }, cityId = city.id)
}


internal fun CivsUi.workerStatus(player: Player, worker: CivWorker): Component {
    val status = host.workerStatus(worker.id)
    return tint(text.status(player, status), statusColor(status))
}
