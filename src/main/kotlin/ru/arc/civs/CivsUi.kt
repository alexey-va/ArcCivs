package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Native screen composition. The host revalidates every domain mutation. */
class CivsUi internal constructor(
    private val plugin: Plugin,
    private val host: CivsUiHost,
    private val tasks: LifecycleTaskScope,
    private val runtime: PaperDialogRuntime,
    private val text: CivsText,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin, host: CivsUiHost, tasks: LifecycleTaskScope, text: CivsText) :
        this(plugin, host, tasks, PaperDialogRuntime(plugin), text)

    private class Visit(val screenId: String, val epoch: LifecycleTaskScope.Token) {
        var consumed = false
        var cancelPending: (() -> Unit)? = null
    }
    private val visits = mutableMapOf<UUID, Visit>()
    private val roots = mutableMapOf<UUID, String>()
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        visits.remove(event.player.uniqueId)
        roots.remove(event.player.uniqueId)
    }

    fun open(player: Player) {
        if (closed || !player.isOnline) return
        runtime.beginFlow(player)
        roots[player.uniqueId] = when {
            !host.ready() -> "civs.loading"
            host.preview(player) != null -> "civs.preview"
            host.city(player) != null -> "civs.overview"
            else -> "civs.welcome"
        }
        showEntry(player)
    }

    fun showingDialog(player: Player): Boolean = player.uniqueId in visits

    /** Forms and details yield to the world instead of reopening a placement modal. */
    fun resumePlacement(player: Player) {
        runtime.close(player)
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        if (host.preview(player) != null) player.sendMessage(tr(player, "panel.opened"))
    }

    fun openWorker(player: Player, cityId: UUID, workerId: UUID) {
        if (closed || !player.isOnline) return
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.worker"
        val city = host.settlements().firstOrNull { it.id == cityId }
        if (city == null || !city.canManage(player.uniqueId)) {
            notice(player, "civs.worker", "workers.title", text.reason(player, "not-member")) { showEntry(player) }
        } else showWorker(player, workerId)
    }

    private fun showEntry(player: Player) {
        when {
            !host.ready() -> present(player, "civs.loading", "common.title",
                listOf(body(player, "common.starting")), buildList {
                    add(button(player, "refresh", "common.refresh") { showEntry(player) })
                    if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
                }, reopen = { showEntry(player) })
            host.preview(player) != null -> showPreview(player)
            host.city(player) != null -> showOverview(player)
            else -> showWelcome(player)
        }
    }

    private fun showWelcome(player: Player): Unit { present(player, "civs.welcome", "common.title",
        listOfNotNull(body(player, "founding.intro"), body(player, "city.sleep-rule"),
            if (host.styles().isEmpty()) body(player, "founding.unavailable") else null), buildList {
            if (host.styles().isNotEmpty()) add(button(player, "found", "founding.begin", PERSONAL) { showStyles(player) })
            else add(button(player, "refresh", "common.refresh") { showWelcome(player) })
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showEntry(player) }) }

    private fun showStyles(player: Player, page: Int = 0) {
        val styles = host.styles()
        val current = page.coerceIn(0, lastPage(styles.size))
        present(player, "civs.styles", "founding.style-title", listOf(body(player, "founding.style-help")),
            buildList {
                styles.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, style ->
                    add(valueButton(player, "style_$index", "common.choice", text.style(player, style)) { showName(player, style) })
                }
                pages(player, current, styles.size) { showStyles(player, it) }
            }, reopen = { showStyles(player, current) })
    }

    private fun showName(player: Player, style: String, initial: String = "", error: Component? = null) {
        var draft = initial
        present(player, "civs.name", "founding.name-title",
            buildList {
                if (error != null) {
                    add(body(tr(player, "founding.prepare-failed").color(DANGER)))
                    add(body(error))
                } else add(body(player, "founding.name-help"))
                add(table(listOf(tr(player, "label.style") to text.style(player, style))))
            },
            listOf(button(player, "prepare", if (error == null) "founding.prepare" else "founding.retry", COMMIT) { context ->
                val name = context.text(NAME)?.trim().orEmpty()
                draft = name
                if (name.isBlank()) showName(player, style, name, text.reason(player, "invalid-name"))
                else perform(player, "civs.preview", "preview.title", { host.prepareFounding(player, name, style) },
                    success = { resumePlacement(player) }, failure = { showName(player, style, name, it) },
                    cancelPending = { host.cancelPreview(player) })
            }),
            inputs = listOf(PaperDialogTextInput(NAME, tr(player, "founding.name-label"), initial = initial.take(32), width = 320, maxLength = 32)),
            reopen = { showName(player, style, draft) },
        )
    }

    private fun showPreview(player: Player, error: Component? = null) {
        val preview = host.preview(player)
        if (preview == null) {
            if (error == null) showEntry(player)
            else notice(player, "civs.preview", "preview.title", error) { showEntry(player) }
            return
        }
        present(player, "civs.preview", "preview.title", listOfNotNull(
            body(player, "preview.help"),
            table(listOf(
                tr(player, "label.building") to buildingName(player, preview.type, preview.level),
                tr(player, "label.city") to text.literal(preview.cityName),
                tr(player, "label.position") to coordinates(player, preview.anchor),
                tr(player, "label.size") to tr(player, "preview.size", "x" to preview.sizeX, "y" to preview.sizeY, "z" to preview.sizeZ),
                tr(player, "label.rotation") to tr(player, "preview.rotation", "value" to preview.rotation),
            )),
            costTable(player, preview.cost), body(player, "preview.protected"), error?.let(::body),
        ), listOf(button(player, "placement", "panel.return", NAVIGATION) { resumePlacement(player) }),
            reopen = { showPreview(player) })
    }

    private fun showOverview(player: Player, feedback: Component? = null) {
        val city = city(player) ?: return
        val sleep = city.workers.isNotEmpty() && city.workers.all {
            host.workerStatus(it.id).substringAfterLast('.') in setOf("sleeping", "no-viewers")
        }
        val construction = city.construction
        present(player, "civs.overview", "city.title", listOfNotNull(
            body(tr(player, "city.intro", "name" to city.name)),
            table(listOf(
                tr(player, "label.level") to text.literal(city.level()),
                tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to CivsRules.populationLimit(city)),
                tr(player, "label.beds") to text.literal(CivsRules.beds(city)),
                tr(player, "label.buildings") to tr(player, "common.fraction", "value" to city.buildings.size, "limit" to CivsRules.MAX_BUILDINGS),
                tr(player, "label.activity") to tr(player, if (sleep) "city.sleeping" else "city.active"),
                tr(player, "label.construction") to constructionText(player, city),
            )),
            construction?.failure?.let { body(text.reason(player, it)) },
            body(nextStep(player, city)), body(player, "city.sleep-rule"), feedback?.let(::body),
        ), buildList {
            add(button(player, "buildings", "buildings.open", LAND) { showBuildings(player) })
            add(button(player, "stock", "stock.open", STOCK) { showStock(player) })
            add(button(player, "workers", "workers.open", PEOPLE) { showWorkers(player) })
            add(button(player, "upgrades", "upgrades.open", PERSONAL) { showUpgrades(player) })
            add(button(player, "members", "members.open") { showMembers(player) })
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showOverview(player) })
    }

    private fun showStock(player: Player) {
        val city = city(player) ?: return
        present(player, "civs.stock", "stock.title", buildList {
            add(body(player, "stock.help"))
            add(table(Resource.entries.map { resource -> text.resource(player, resource) to
                tr(player, "common.fraction", "value" to city.stock[resource], "limit" to CivsRules.capacity(city)) }))
            city.construction?.let { add(body(tr(player, "stock.reserved", "cost" to cost(player, it.cost)))) }
            add(body(player, "stock.no-export"))
        }, listOf(button(player, "refresh", "common.refresh") { showStock(player) }), reopen = { showStock(player) })
    }

    private fun showBuildings(player: Player, page: Int = 0) {
        val city = city(player) ?: return
        val current = page.coerceIn(0, lastPage(city.buildings.size))
        present(player, "civs.buildings", "buildings.title", listOf(
            body(player, "buildings.help"), table(listOf(tr(player, "label.construction") to constructionText(player, city))),
        ), buildList {
            city.buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
                add(valueButton(player, "building_$index", "common.destination", buildingName(player, building.type, building.level), LAND) {
                    showBuilding(player, building.id)
                })
            }
            if (city.buildings.size < CivsRules.MAX_BUILDINGS && city.construction == null) add(button(player, "catalog", "buildings.catalog", PERSONAL) { showCatalog(player) })
            pages(player, current, city.buildings.size) { showBuildings(player, it) }
        }, reopen = { showBuildings(player, current) })
    }

    private fun showCatalog(player: Player) {
        city(player) ?: return
        present(player, "civs.catalog", "buildings.catalog-title", listOf(body(player, "buildings.catalog-help")),
            BuildingType.entries.filter { it != BuildingType.TOWN_HALL }.mapIndexed { index, type ->
                valueButton(player, "offer_$index", "common.destination", text.building(player, type), LAND) { showOffer(player, type) }
            }, reopen = { if (city(player) != null) showCatalog(player) })
    }

    private fun showOffer(player: Player, type: BuildingType, error: Component? = null) {
        val city = city(player) ?: return
        val price = CivsRules.cost(type, 1)
        val eligible = city.construction == null && city.buildings.size < CivsRules.MAX_BUILDINGS && city.stock.covers(price)
        present(player, "civs.offer", "buildings.offer-title", listOfNotNull(
            body(buildingName(player, type, 1)), body(player, "building-purpose.${type.name.lowercase(Locale.ROOT)}"),
            costTable(player, price, city.stock),
            when {
                city.construction != null -> body(player, "reason.construction-busy")
                city.buildings.size >= CivsRules.MAX_BUILDINGS -> body(player, "reason.building-limit")
                !city.stock.covers(price) -> body(player, "reason.insufficient-resources")
                else -> null
            }, error?.let(::body),
        ), if (eligible) listOf(button(player, "prepare", "buildings.prepare", COMMIT) {
            perform(player, "civs.preview", "preview.title", { host.prepareBuilding(player, type) },
                success = { resumePlacement(player) }, failure = { showOffer(player, type, it) },
                cancelPending = { host.cancelPreview(player) })
        }) else emptyList(), reopen = { showOffer(player, type) })
    }

    private fun showBuilding(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val assigned = city.workers.count { it.assignment == buildingId }
        present(player, "civs.building", "buildings.detail-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            body(player, "building-purpose.${building.type.name.lowercase(Locale.ROOT)}"),
            table(buildList {
                add(tr(player, "label.position") to coordinates(player, building.anchor))
                add(tr(player, "label.state") to tr(player, "building-phase.${building.phase.name.lowercase(Locale.ROOT)}"))
                add(tr(player, "label.workers") to tr(player, "common.fraction", "value" to assigned, "limit" to CivsRules.slots(building)))
                CivsRules.resource(building.type)?.let { resource ->
                    add(tr(player, "label.output") to tr(player, "buildings.output", "amount" to CivsRules.deliveryAmount(building), "resource" to text.resource(player, resource)))
                }
                if (city.construction?.buildingId == buildingId) add(tr(player, "label.construction") to constructionText(player, city))
            }), error?.let(::body),
        ), buildList {
            if (CivsRules.slots(building) > 0) add(button(player, "workers", "buildings.workers", PEOPLE) { showWorkers(player) })
            if (building.level < 3 && building.phase == BuildingPhase.ACTIVE) add(button(player, "upgrade", "upgrades.open", PERSONAL) { showUpgrade(player, building.id) })
        }, reopen = { showBuilding(player, buildingId) })
    }

    private fun showUpgrades(player: Player, page: Int = 0) {
        val city = city(player) ?: return
        val buildings = city.buildings.filter { it.level < 3 && it.phase == BuildingPhase.ACTIVE }
        val current = page.coerceIn(0, lastPage(buildings.size))
        present(player, "civs.upgrades", "upgrades.title", listOf(body(player,
            if (buildings.isEmpty()) "upgrades.complete" else "upgrades.help")), buildList {
            buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
                add(valueButton(player, "upgrade_$index", "common.destination", buildingName(player, building.type, building.level), PERSONAL) {
                    showUpgrade(player, building.id)
                })
            }
            pages(player, current, buildings.size) { showUpgrades(player, it) }
        }, reopen = { showUpgrades(player, current) })
    }

    private fun showUpgrade(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (building.level >= 3) return showBuilding(player, buildingId)
        val target = building.level + 1
        val price = CivsRules.cost(building.type, target)
        val hallRequired = building.type != BuildingType.TOWN_HALL && target > city.level()
        val eligible = !hallRequired && city.construction == null && city.stock.covers(price) && building.phase == BuildingPhase.ACTIVE
        present(player, "civs.upgrade", "upgrades.detail-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(listOf(
                tr(player, "label.level") to tr(player, "common.change", "before" to building.level, "after" to target),
                tr(player, "label.benefit") to upgradeBenefit(player, building),
            )), costTable(player, price, city.stock),
            when {
                city.construction != null -> body(player, "reason.construction-busy")
                hallRequired -> body(tr(player, "upgrades.hall-required", "level" to target))
                !city.stock.covers(price) -> body(player, "reason.insufficient-resources")
                else -> null
            }, error?.let(::body),
        ), if (eligible) listOf(button(player, "prepare", "upgrades.prepare", COMMIT) {
            perform(player, "civs.preview", "preview.title", { host.prepareUpgrade(player, buildingId) },
                success = { resumePlacement(player) }, failure = { showUpgrade(player, buildingId, it) },
                cancelPending = { host.cancelPreview(player) })
        }) else emptyList(), reopen = { showUpgrade(player, buildingId) })
    }

    private fun showWorkers(player: Player, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val current = page.coerceIn(0, lastPage(city.workers.size))
        val limit = minOf(CivsRules.populationLimit(city), CivsRules.beds(city))
        present(player, "civs.workers", "workers.title", listOfNotNull(
            body(player, "workers.help"), table(listOf(
                tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to limit),
                tr(player, "label.unassigned") to text.literal(city.workers.count { it.assignment == null }),
            )), error?.let(::body),
        ), buildList {
            city.workers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, worker ->
                add(valueButton(player, "worker_$index", "common.destination", text.literal(worker.name), PEOPLE,
                    workerStatus(player, worker)) { showWorker(player, worker.id) })
            }
            if (city.workers.size < limit) add(button(player, "recruit", "workers.recruit", COMMIT) { showRecruit(player) })
            pages(player, current, city.workers.size) { showWorkers(player, it) }
        }, reopen = { showWorkers(player, current) })
    }

    private fun showWorker(player: Player, workerId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
        val building = worker.assignment?.let(city::building)
        present(player, "civs.worker", "workers.detail-title", listOfNotNull(
            body(text.literal(worker.name)), table(listOf(
                tr(player, "label.workplace") to (building?.let { buildingName(player, it.type, it.level) } ?: tr(player, "workers.unassigned")),
                tr(player, "label.profession") to tr(player, "profession.${building?.let { CivsRules.profession(it.type) } ?: "unassigned"}"),
                tr(player, "label.state") to workerStatus(player, worker),
                tr(player, "label.deliveries") to text.literal(worker.completedCycles),
            )), error?.let(::body),
        ), buildList {
            add(button(player, "assign", "workers.assign", PERSONAL) { showAssignments(player, workerId) })
            if (worker.assignment != null) add(button(player, "unassign", "workers.unassign") {
                perform(player, "civs.worker", "workers.detail-title", { host.assign(player, workerId, null) },
                    success = { showWorker(player, workerId) }, failure = { showWorker(player, workerId, it) })
            })
        }, reopen = { showWorker(player, workerId) })
    }

    private fun showAssignments(player: Player, workerId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
        val places = city.buildings.filter { building -> building.phase == BuildingPhase.ACTIVE && CivsRules.slots(building) > 0 &&
            (worker.assignment == building.id || city.workers.count { it.assignment == building.id } < CivsRules.slots(building)) }
        val current = page.coerceIn(0, lastPage(places.size))
        present(player, "civs.assign", "workers.assign-title", listOfNotNull(
            body(tr(player, if (places.isEmpty()) "workers.no-jobs" else "workers.assign-help", "name" to worker.name)), error?.let(::body),
        ), buildList {
            places.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
                val selected = worker.assignment == building.id
                add(valueButton(player, "assign_$index", if (selected) "common.selected" else "common.choice",
                    buildingName(player, building.type, building.level), if (selected) COMMIT else WHITE) {
                    if (selected) showWorker(player, workerId)
                    else perform(player, "civs.assign", "workers.assign-title", { host.assign(player, workerId, building.id) },
                        success = { showWorker(player, workerId) }, failure = { showAssignments(player, workerId, current, it) })
                })
            }
            pages(player, current, places.size) { showAssignments(player, workerId, it) }
        }, reopen = { showAssignments(player, workerId, current) })
    }

    private fun showRecruit(player: Player, error: Component? = null) {
        val city = city(player) ?: return
        val eligible = city.workers.size < minOf(CivsRules.beds(city), CivsRules.populationLimit(city)) && city.stock.food >= CivsRules.RECRUIT_FOOD
        present(player, "civs.recruit", "workers.recruit-title", listOfNotNull(
            body(player, "workers.recruit-help"), costTable(player, Stock(food = CivsRules.RECRUIT_FOOD), city.stock),
            if (!eligible) body(player, if (city.stock.food < CivsRules.RECRUIT_FOOD) "reason.insufficient-resources" else "reason.population-limit") else null,
            error?.let(::body),
        ), if (eligible) listOf(button(player, "confirm", "workers.recruit-confirm", COMMIT) {
            perform(player, "civs.recruit", "workers.recruit-title", { host.recruit(player) },
                success = { showWorkers(player) }, failure = { showRecruit(player, it) })
        }) else emptyList(), reopen = { showRecruit(player) })
    }

    private fun showMembers(player: Player, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val members = city.members.sortedBy(UUID::toString)
        val current = page.coerceIn(0, lastPage(members.size))
        present(player, "civs.members", "members.title", listOfNotNull(
            body(player, "members.help"), table(listOf(tr(player, "label.owner") to playerName(player, city.owner))), error?.let(::body),
        ), buildList {
            members.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, member ->
                add(valueButton(player, "member_$index", "common.destination", playerName(player, member)) { showMember(player, member) })
            }
            if (city.owner == player.uniqueId) add(button(player, "add", "members.add", PERSONAL) { showAddMember(player) })
            pages(player, current, members.size) { showMembers(player, it) }
        }, reopen = { showMembers(player, current) })
    }

    private fun showAddMember(player: Player, initial: String = "", error: Component? = null) {
        val city = city(player) ?: return
        if (city.owner != player.uniqueId) return showMembers(player)
        present(player, "civs.member-add", "members.add-title", listOfNotNull(body(player, "members.add-help"), error?.let(::body)),
            listOf(button(player, "add", "members.add-confirm", COMMIT) { context ->
                val name = context.text(MEMBER)?.trim().orEmpty()
                perform(player, "civs.member-add", "members.add-title", { host.addMember(player, name) },
                    success = { showMembers(player) }, failure = { showAddMember(player, name, it) })
            }), inputs = listOf(PaperDialogTextInput(MEMBER, tr(player, "members.name-label"), initial = initial.take(16), width = 320, maxLength = 16)))
    }

    private fun showMember(player: Player, member: UUID, error: Component? = null) {
        val city = city(player) ?: return
        if (member !in city.members) return showMembers(player)
        present(player, "civs.member", "members.detail-title", listOfNotNull(
            body(tr(player, "members.detail", "name" to playerName(player, member))), error?.let(::body),
        ), if (city.owner == player.uniqueId) listOf(button(player, "remove", "members.remove", DANGER) {
            perform(player, "civs.member", "members.detail-title", { host.removeMember(player, member) },
                success = { showMembers(player) }, failure = { showMember(player, member, it) })
        }) else emptyList(), reopen = { showMember(player, member) })
    }

    private fun showAdmin(player: Player, page: Int = 0, error: Component? = null) {
        if (!isAdmin(player)) return showEntry(player)
        val cities = host.settlements().sortedBy { it.name }
        val current = page.coerceIn(0, lastPage(cities.size))
        present(player, "civs.admin", "admin.title", listOfNotNull(table(listOf(
            tr(player, "label.state") to tr(player, if (host.ready()) "admin.ready" else "admin.starting"),
            tr(player, "label.cities") to text.literal(cities.size),
            tr(player, "label.active-npcs") to tr(player, "common.fraction", "value" to host.activeNpcCount(),
                "limit" to plugin.config.getInt("maximum-active-npcs", 48)),
        )), error?.let(::body)), buildList {
            cities.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, city ->
                add(valueButton(player, "city_$index", "common.destination", text.literal(city.name)) { showAdminCity(player, city.id) })
            }
            add(button(player, "reload", "admin.reload", PERSONAL) { showReload(player) })
            add(button(player, "refresh", "common.refresh") { showAdmin(player, current) })
            pages(player, current, cities.size) { showAdmin(player, it) }
        }, reopen = { showAdmin(player, current) })
    }

    private fun showAdminCity(player: Player, cityId: UUID, error: Component? = null) {
        if (!isAdmin(player)) return showEntry(player)
        val city = host.settlements().firstOrNull { it.id == cityId } ?: return showAdmin(player)
        present(player, "civs.admin-city", "admin.city-title", listOfNotNull(
            body(text.literal(city.name)), table(listOf(
                tr(player, "label.owner") to playerName(player, city.owner),
                tr(player, "label.city-id") to text.literal(city.id),
                tr(player, "label.construction") to constructionText(player, city),
            )), city.construction?.failure?.let { body(text.reason(player, it)) }, error?.let(::body),
        ), buildList {
            if (city.construction?.phase == ConstructionPhase.PAUSED) add(button(player, "retry", "admin.retry", COMMIT) {
                perform(player, "civs.admin-city", "admin.city-title", { host.retryConstruction(player, cityId) },
                    success = { showAdminCity(player, cityId) }, failure = { showAdminCity(player, cityId, it) })
            })
            add(button(player, "refresh", "common.refresh") { showAdminCity(player, cityId) })
        }, reopen = { showAdminCity(player, cityId) })
    }

    private fun showReload(player: Player, error: Component? = null) {
        if (!isAdmin(player)) return showEntry(player)
        present(player, "civs.admin-reload", "admin.reload-title", listOfNotNull(body(player, "admin.reload-help"), error?.let(::body)),
            listOf(button(player, "confirm", "admin.reload-confirm", COMMIT) {
                perform(player, "civs.admin-reload", "admin.reload-title", { host.reload(player) },
                    success = { showAdmin(player) }, failure = { showReload(player, it) })
            }), reopen = { showReload(player) })
    }

    private fun nextStep(player: Player, city: Settlement): Component {
        val key = when {
            city.construction?.phase == ConstructionPhase.PAUSED -> "city.next-paused"
            city.construction != null && city.workers.none { worker -> worker.assignment?.let(city::building)?.type == BuildingType.TOWN_HALL } -> "city.next-builder"
            city.construction != null -> "city.next-building"
            city.buildings.none { it.type == BuildingType.WAREHOUSE } -> "city.next-warehouse"
            listOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM).any { type -> city.buildings.none { it.type == type } } -> "city.next-production"
            city.workers.any { it.assignment == null } -> "city.next-assign"
            city.workers.size < CivsRules.populationLimit(city) && city.workers.size >= CivsRules.beds(city) -> "city.next-house"
            city.level() < 3 -> "city.next-upgrade"
            else -> "city.next-grow"
        }
        return tr(player, key)
    }

    private fun workerStatus(player: Player, worker: CivWorker): Component {
        val status = host.workerStatus(worker.id).removePrefix("workers.status.")
        // Durable assignments can change before the next Citizens pulse updates its cached status.
        return text.status(player, when {
            worker.assignment == null -> "unassigned"
            status == "unassigned" || status == "no-assignment" -> "waiting"
            else -> status
        })
    }

    private fun upgradeBenefit(player: Player, building: CivBuilding): Component = when (building.type) {
        BuildingType.TOWN_HALL -> tr(player, "upgrades.population", "before" to building.level * 4, "after" to (building.level + 1) * 4)
        BuildingType.HOUSE -> tr(player, "upgrades.beds", "before" to building.level * 2, "after" to (building.level + 1) * 2)
        BuildingType.WAREHOUSE -> tr(player, "upgrades.capacity", "before" to (256L shl (building.level - 1)), "after" to (256L shl building.level))
        else -> tr(player, "upgrades.production", "before" to CivsRules.deliveryAmount(building),
            "after" to CivsRules.deliveryAmount(building.copy(level = building.level + 1)), "slots" to CivsRules.slots(building.copy(level = building.level + 1)))
    }

    private fun constructionText(player: Player, city: Settlement): Component {
        val order = city.construction ?: return tr(player, "city.no-construction")
        val planned = order.plannedBuilding ?: city.building(order.buildingId)
        return tr(player, "city.construction", "building" to (planned?.let { text.building(player, it.type) } ?: tr(player, "buildings.generic")),
            "done" to order.workDone, "total" to order.workRequired,
            "state" to tr(player, "construction-phase.${order.phase.name.lowercase(Locale.ROOT)}"))
    }

    private fun city(player: Player): Settlement? {
        val city = host.city(player)
        if (city == null || !city.canManage(player.uniqueId)) {
            showWelcome(player)
            return null
        }
        return city
    }

    private fun perform(player: Player, id: String, title: String, action: () -> CompletableFuture<Unit>,
        success: () -> Unit, failure: (Component) -> Unit, cancelPending: (() -> Unit)? = null) {
        val visit = present(player, id, title, listOf(body(player, "common.working")), emptyList()) ?: return
        visit.cancelPending = cancelPending
        val future = try { action() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { _, error ->
            if (!current(player, visit)) return@whenCompleteSync
            visit.cancelPending = null
            if (error == null) success() else failure(text.failure(player, error))
        }
    }

    private fun notice(player: Player, id: String, title: String, content: Component, back: () -> Unit) =
        present(player, id, title, listOf(body(content)), listOf(button(player, "return", "common.return") { back() }))

    private fun present(player: Player, id: String, title: String, bodies: List<PaperDialogBody>, buttons: List<PaperDialogButton>,
        inputs: List<PaperDialogTextInput> = emptyList(), reopen: (() -> Unit)? = null): Visit? {
        if (closed || !player.isOnline) return null
        val visit = Visit(id, tasks.token())
        visits[player.uniqueId] = visit
        val closeOnEscape = player.getMetadata("arc-menu-escape").any { it.asString() == "close" }
        val footerKey = if (closeOnEscape || roots[player.uniqueId] == id) "common.close" else "common.back"
        val guarded = buttons.map { button -> button.copy(closeDialogBeforeAction = false, onClick = { context ->
            if (current(player, visit) && !visit.consumed) {
                visit.consumed = true
                if (button.closeDialogBeforeAction) runtime.close(player)
                button.onClick.handle(context)
            }
        }) }
        runtime.open(player, PaperDialogScreen(id = id, title = tr(player, title).color(LAND), body = bodies,
            inputs = inputs, buttons = guarded, columns = if (guarded.size == 1) 1 else 2,
            exitButton = button(player, "history", footerKey).copy(width = 200),
        ), reopen, {
            if (visits[player.uniqueId] === visit) {
                visits.remove(player.uniqueId)
                visit.cancelPending?.invoke()
            }
        }, closeOnEscape)
        return visit
    }

    private fun current(player: Player, visit: Visit): Boolean =
        !closed && player.isOnline && tasks.isCurrent(visit.epoch) && visits[player.uniqueId] === visit

    private fun button(player: Player, id: String, key: String, color: TextColor = WHITE,
        close: Boolean = false, action: ru.arc.paper.menu.PaperDialogClickHandler = ru.arc.paper.menu.PaperDialogClickHandler {}): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id), tr(player, key).color(color), width = 230,
            closeDialogBeforeAction = close, onClick = action)

    private fun valueButton(player: Player, id: String, key: String, value: Component, color: TextColor = WHITE,
        tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id), tr(player, key, "value" to value).color(color), tooltip,
            width = 230, onClick = { action() })

    private fun MutableList<PaperDialogButton>.pages(player: Player, page: Int, count: Int, show: (Int) -> Unit) {
        if (page > 0) add(button(player, "previous", "common.previous", NAVIGATION) { show(page - 1) })
        if (page < lastPage(count)) add(button(player, "next", "common.next", NAVIGATION) { show(page + 1) })
    }

    private fun lastPage(count: Int): Int = ((count - 1).coerceAtLeast(0)) / PAGE_SIZE
    private fun isAdmin(player: Player): Boolean = player.hasPermission("arccivs.admin")
    private fun tr(player: Player, key: String, vararg values: Pair<String, Any?>): Component = text.render(player, key, *values)
    private fun body(player: Player, key: String): PaperDialogBody = body(tr(player, key))
    private fun body(component: Component): PaperDialogBody = PaperDialogBody(component, 320)
    private fun table(rows: List<Pair<Component, Component>>): PaperDialogBody = DialogTables.body(
        rows.map { (label, value) -> label.color(CivsText.BODY) to value.colorIfAbsent(WHITE) },
        null, DialogTables.Frame.EPIC, 320, DialogTables.Columns.VALUE_WIDE,
    )
    private fun costTable(player: Player, cost: Stock, available: Stock? = null): PaperDialogBody = if (cost == Stock()) body(player, "founding.no-cost") else table(
        Resource.entries.filter { cost[it] > 0 }.map { resource -> text.resource(player, resource) to
            if (available == null) text.literal(cost[resource])
            else tr(player, "common.required", "have" to available[resource], "need" to cost[resource]) },
    )
    private fun cost(player: Player, value: Stock): Component = tr(player, "stock.cost", "wood" to value.wood, "stone" to value.stone, "food" to value.food)
    private fun buildingName(player: Player, type: BuildingType, level: Int): Component = tr(player, "buildings.name", "name" to text.building(player, type), "level" to level)
    private fun coordinates(player: Player, pos: CivPosition): Component = tr(player, "common.coordinates", "x" to pos.x, "y" to pos.y, "z" to pos.z)
    private fun playerName(viewer: Player, id: UUID): Component = plugin.server.getPlayer(id)?.name?.let(text::literal)
        ?: tr(viewer, "members.offline", "id" to id.toString().take(8))

    override fun close() {
        if (closed) return
        closed = true
        visits.clear()
        roots.clear()
        HandlerList.unregisterAll(this)
        runtime.close()
    }

    private companion object {
        const val PAGE_SIZE = 8
        val NAME = PaperDialogInputId.of("city_name")
        val MEMBER = PaperDialogInputId.of("member_name")
        val WHITE = TextColor.color(0xFFFFFF)
        val LAND = TextColor.color(0x9BD48D)
        val COMMIT = TextColor.color(0x9BD48D)
        val PERSONAL = TextColor.color(0xC4A7E7)
        val PEOPLE = TextColor.color(0xF3A2C9)
        val STOCK = TextColor.color(0xF4D87A)
        val NAVIGATION = TextColor.color(0x92BED8)
        val DANGER = TextColor.color(0xFF6B61)
    }
}
