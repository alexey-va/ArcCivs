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
        val id: UUID = UUID.randomUUID()
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
        if (closed || !player.isOnline) {
            CivsDiagnostics.event("ui_open_ignored", "player" to player.uniqueId, "closed" to closed, "online" to player.isOnline)
            return
        }
        CivsDiagnostics.event("ui_open", *CivsDiagnostics.actor(player))
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        if (!host.ready()) {
            roots[player.uniqueId] = "civs.loading"
            showEntry(player)
            return
        }
        roots[player.uniqueId] = "civs.lookup"
        perform(player, "civs.lookup", "common.title", { host.refreshCity(player) }, success = {
            runtime.beginFlow(player)
            openEntry(player)
        }, failure = { error ->
            runtime.beginFlow(player)
            roots[player.uniqueId] = "civs.network-error"
            present(player, "civs.network-error", "common.title", listOf(
                body(tr(player, "network.lookup-failed").color(DANGER)), body(error)),
                listOf(button(player, "refresh", "common.refresh") { open(player) }), reopen = { open(player) })
        }, workingKey = "network.loading")
    }

    private fun openEntry(player: Player) {
        roots[player.uniqueId] = when {
            !host.ready() -> "civs.loading"
            host.preview(player) != null -> "civs.preview"
            host.city(player) != null -> "civs.overview"
            host.remoteCity(player) != null -> "civs.remote"
            else -> "civs.welcome"
        }
        showEntry(player)
    }

    fun showingDialog(player: Player): Boolean = player.uniqueId in visits

    /** Forms and details yield to the world instead of reopening a placement modal. */
    fun resumePlacement(player: Player) {
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        if (host.selection(player) != null) player.sendActionBar(tr(player, "selection.hint"))
        else if (host.preview(player) != null) player.sendMessage(tr(player, "panel.opened"))
    }

    fun openWorker(player: Player, cityId: UUID, workerId: UUID) {
        if (closed || !player.isOnline) return
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.worker"
        val city = host.settlements().firstOrNull { it.id == cityId }
        if (city == null || !city.canManage(player.uniqueId)) {
            notice(player, "civs.worker", "workers.title", text.reason(player, "not-member")) { showEntry(player) }
        } else showWorker(player, workerId)
    }

    /** The control block and the town catalog open the same building screen. */
    fun openBuilding(player: Player, cityId: UUID, buildingId: UUID) {
        if (closed || !player.isOnline) return
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.building"
        val city = host.settlements().firstOrNull { it.id == cityId }
        if (city == null || !city.canManage(player.uniqueId)) {
            notice(player, "civs.building", "buildings.detail-title", text.reason(player, "not-member")) { showEntry(player) }
        } else showBuilding(player, buildingId)
    }

    private fun showEntry(player: Player) {
        when {
            !host.ready() -> present(player, "civs.loading", "common.title",
                host.unavailableReason()?.let { listOf(body(tr(player, "network.lookup-failed").color(DANGER)),
                    body(text.reason(player, it))) } ?: listOf(body(player, "common.starting")), buildList {
                    add(button(player, "refresh", "common.refresh") { open(player) })
                    if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
                }, reopen = { showEntry(player) })
            host.preview(player) != null -> showPreview(player)
            host.city(player) != null -> showOverview(player)
            host.remoteCity(player) != null -> showRemote(player)
            else -> showWelcome(player)
        }
    }

    private fun showWelcome(player: Player): Unit { present(player, "civs.welcome", "common.title",
        listOfNotNull(body(player, if (host.foundingAllowed()) "founding.intro" else "network.view-only"), body(player, "city.sleep-rule"),
            if (host.styles().isEmpty()) body(player, "founding.unavailable") else null), buildList {
            if (host.foundingAllowed() && host.styles().isNotEmpty()) add(button(player, "found", "founding.begin", PERSONAL) { showStyles(player) })
            else add(button(player, "refresh", "common.refresh") { open(player) })
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showEntry(player) }) }

    private fun showRemote(player: Player, feedback: Component? = null) {
        val remote = host.remoteCity(player) ?: return showEntry(player)
        val city = remote.city
        present(player, "civs.remote", "city.title", listOfNotNull(
            body(tr(player, "city.intro", "name" to city.name)),
            body(tr(player, "network.remote-help", "server" to remote.server.value)),
            table(listOf(
                tr(player, "network.server-label") to text.literal(remote.server.value),
                tr(player, "label.level") to text.literal(city.level()),
                tr(player, "label.radius") to tr(player, "city.radius", "radius" to city.radius),
                tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to CivsRules.populationLimit(city)),
                tr(player, "label.buildings") to text.literal(city.buildings.size),
                tr(player, "label.construction") to constructionText(player, city),
            )),
            city.inventory?.let { inventory -> table(listOf(
                tr(player, "stock.reserve-label") to tr(player, "stock.items", "amount" to inventory.materials.values.sum()),
                tr(player, "stock.building-total") to tr(player, "stock.items", "amount" to inventory.buildingMaterials.orEmpty().values.sumOf { it.values.sum() }),
            )) } ?: table(Resource.entries.map { resource -> text.resource(player, resource) to text.literal(city.stock[resource]) }),
            if (city.inventory != null) body(player, "network.storage-snapshot-help") else null,
            body(player, "network.snapshot-help"), feedback?.let(::body),
        ), listOf(
            valueButton(player, "transfer", "network.transfer", text.literal(remote.server.value), NAVIGATION) {
                perform(player, "civs.remote", "city.title", { host.transferHome(player) },
                    success = { showRemote(player, tr(player, "network.transfer-requested", "server" to remote.server.value)) },
                    failure = { showRemote(player, it) }, workingKey = "network.connecting")
            },
            button(player, "refresh", "common.refresh") { open(player) },
        ), reopen = { showRemote(player) })
    }

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
                else beginSelection(player, { host.beginFoundingSelection(player, name, style) }) {
                    showName(player, style, name, it)
                }
            }),
            inputs = listOf(PaperDialogTextInput(NAME, tr(player, "founding.name-label"), initial = initial.take(32), width = 320, maxLength = 32)),
            reopen = { showName(player, style, draft) },
        )
    }

    private fun showPreview(player: Player, error: Component? = null, page: Int = 0) {
        val preview = host.preview(player)
        if (preview == null) {
            if (error == null) showEntry(player)
            else notice(player, "civs.preview", "preview.title", error) { showEntry(player) }
            return
        }
        val materials = preview.materials.toSortedMap().entries.toList()
        val current = page.coerceIn(0, lastPage(materials.size))
        present(player, "civs.preview", "preview.title", listOfNotNull(
            body(player, if (preview.movable) "preview.site-help" else "preview.upgrade-help"),
            table(listOf(
                tr(player, "label.building") to buildingName(player, preview.type, preview.level),
                tr(player, "label.city") to text.literal(preview.cityName),
                tr(player, "label.position") to coordinates(player, preview.anchor),
                tr(player, "label.size") to tr(player, "preview.size", "x" to preview.sizeX, "y" to preview.sizeY, "z" to preview.sizeZ),
                tr(player, "label.rotation") to tr(player, "preview.rotation", "value" to preview.rotation),
            )),
            if (materials.isNotEmpty()) table(materials.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { (material, count) ->
                materialName(player, material) to tr(player, "materials.up-to", "amount" to count)
            }) else null,
            if (materials.isNotEmpty()) body(player, "materials.estimate-help") else null,
            body(player, "preview.protected"), error?.let(::body),
        ), buildList {
            add(button(player, "placement", "panel.return", NAVIGATION) { resumePlacement(player) })
            pages(player, current, materials.size) { showPreview(player, error, it) }
        }, reopen = { showPreview(player, page = current) })
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
                tr(player, "label.radius") to tr(player, "city.radius", "radius" to city.radius),
                tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to CivsRules.populationLimit(city)),
                tr(player, "label.beds") to text.literal(CivsRules.beds(city)),
                tr(player, "label.buildings") to tr(player, "common.fraction", "value" to city.buildings.size, "limit" to CivsRules.MAX_BUILDINGS),
                tr(player, "label.activity") to tr(player, if (sleep) "city.sleeping" else "city.active"),
                tr(player, "label.construction") to constructionText(player, city),
            )),
            construction?.failure?.let { body(text.reason(player, it)) },
            body(nextStep(player, city)), body(player, "city.boundary-help"), body(player, "city.sleep-rule"), busyBody(player), feedback?.let(::body),
        ), buildList {
            add(button(player, "buildings", "buildings.open", LAND) { showBuildings(player) })
            add(button(player, "stock", "stock.open", STOCK) { showStock(player) })
            add(button(player, "workers", "workers.open", PEOPLE) { showWorkers(player) })
            add(button(player, "upgrades", "upgrades.open", PERSONAL) { showUpgrades(player) })
            add(button(player, "members", "members.open") { showMembers(player) })
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showOverview(player) })
    }

    private fun showStock(player: Player, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val inventory = city.inventory
        if (inventory == null) {
            notice(player, "civs.stock", "stock.title", text.reason(player, "materials-migration-pending")) { showOverview(player) }
            return
        }
        val current = page.coerceIn(0, lastPage(city.buildings.size))
        present(player, "civs.stock", "stock.title", listOfNotNull(
            body(player, "stock.help"), table(listOf(
                tr(player, "stock.mode-label") to tr(player, "stock.mode.${inventory.mode.name.lowercase(Locale.ROOT)}"),
                tr(player, "stock.reserve-label") to tr(player, "stock.items", "amount" to inventory.materials.values.sum()),
                tr(player, "stock.containers-label") to tr(player, "common.fraction", "value" to inventory.containers.size, "limit" to CivInventory.MAX_CONTAINERS),
            )), body(player, "stock.reserve-help"), busyBody(player), error?.let(::body),
        ), buildList {
            add(button(player, "materials", "stock.available", STOCK) { showAvailableMaterials(player) })
            add(button(player, "mode", "stock.mode-open") { showStorageMode(player) })
            add(button(player, "containers", "stock.containers-open") { showContainers(player) })
            city.buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
                add(valueButton(player, "warehouse_$index", "common.destination", buildingName(player, building.type, building.level), STOCK) {
                    showWarehouse(player, building.id)
                })
            }
            pages(player, current, city.buildings.size) { showStock(player, it) }
            add(button(player, "refresh", "common.refresh") { showStock(player, current) })
        }, reopen = { showStock(player, current) })
    }

    private fun showAvailableMaterials(player: Player, page: Int = 0) {
        city(player) ?: return
        val result = runCatching { host.availableMaterials(player).toSortedMap().entries.toList() }
        val materials = result.getOrDefault(emptyList())
        val current = page.coerceIn(0, lastPage(materials.size))
        present(player, "civs.materials", "stock.available-title", listOfNotNull(
            body(player, "stock.available-help"),
            if (materials.isEmpty() && result.isSuccess) body(player, "stock.empty") else null,
            if (materials.isNotEmpty()) table(materials.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { (material, count) ->
                materialName(player, material) to text.literal(count)
            }) else null,
            result.exceptionOrNull()?.let { body(text.failure(player, it)) }, busyBody(player),
        ), buildList {
            pages(player, current, materials.size) { showAvailableMaterials(player, it) }
            add(button(player, "refresh", "common.refresh") { showAvailableMaterials(player, current) })
        }, reopen = { showAvailableMaterials(player, current) })
    }

    private fun showStorageMode(player: Player, error: Component? = null) {
        val inventory = city(player)?.inventory ?: return showStock(player)
        val busy = host.cityBusy(player)
        present(player, "civs.storage-mode", "stock.mode-title", listOfNotNull(
            body(player, "stock.mode-help"), body(player, "stock.mode-keeps-items"), busyBody(player), error?.let(::body),
        ), buildList {
            if (!busy) CivStorageMode.entries.forEach { mode ->
                val selected = inventory.mode == mode
                add(valueButton(player, "mode_${mode.name.lowercase(Locale.ROOT)}", if (selected) "common.selected" else "common.choice",
                    tr(player, "stock.mode.${mode.name.lowercase(Locale.ROOT)}"), if (selected) COMMIT else WHITE) {
                    if (selected) showStorageMode(player)
                    else perform(player, "civs.storage-mode", "stock.mode-title", { host.setStorageMode(player, mode) },
                        success = { showStorageMode(player) }, failure = { showStorageMode(player, it) })
                })
            }
            add(button(player, "refresh", "common.refresh") { showStorageMode(player) })
        }, reopen = { showStorageMode(player) })
    }

    private fun showContainers(player: Player, page: Int = 0, error: Component? = null) {
        val inventory = city(player)?.inventory ?: return showStock(player)
        val current = page.coerceIn(0, lastPage(inventory.containers.size))
        val busy = host.cityBusy(player)
        present(player, "civs.containers", "stock.containers-title", listOfNotNull(
            body(player, "stock.containers-help"), body(player, "stock.register-help"),
            if (inventory.containers.isEmpty()) body(player, "stock.no-containers") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            inventory.containers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, position ->
                add(valueButton(player, "container_$index", "common.destination", coordinates(player, position)) {
                    showContainer(player, position)
                })
            }
            if (!busy && inventory.containers.size < CivInventory.MAX_CONTAINERS) add(button(player, "register", "stock.register", COMMIT) {
                perform(player, "civs.containers", "stock.containers-title", { host.registerContainer(player) },
                    success = { showContainers(player, current) }, failure = { showContainers(player, current, it) })
            })
            pages(player, current, inventory.containers.size) { showContainers(player, it) }
            add(button(player, "refresh", "common.refresh") { showContainers(player, current) })
        }, reopen = { showContainers(player, current) })
    }

    private fun showContainer(player: Player, position: CivPosition, error: Component? = null) {
        val city = city(player) ?: return
        if (position !in city.inventory?.containers.orEmpty()) return showContainers(player)
        present(player, "civs.container", "stock.container-title", listOfNotNull(
            table(listOf(tr(player, "label.position") to coordinates(player, position))),
            body(player, "stock.unregister-help"), busyBody(player), error?.let(::body),
        ), buildList {
            if (!host.cityBusy(player)) add(button(player, "unregister", "stock.unregister", DANGER) {
                perform(player, "civs.container", "stock.container-title", { host.unregisterContainer(player, position) },
                    success = { showContainers(player) }, failure = { showContainer(player, position, it) })
            })
            add(button(player, "refresh", "common.refresh") { showContainer(player, position) })
        }, reopen = { showContainer(player, position) })
    }

    private fun showWarehouse(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val inventory = city.inventory ?: return showStock(player)
        val own = inventory.buildingStock(buildingId)
        val reserve = inventory.materials
        val materials = (own.keys + reserve.keys).sorted()
        val current = page.coerceIn(0, lastPage(materials.size))
        val busy = host.cityBusy(player)
        present(player, "civs.warehouse", "warehouse.title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(listOf(tr(player, "warehouse.capacity") to tr(player, "common.fraction",
                "value" to own.values.sum(), "limit" to CivInventory.buildingCapacity(building)))),
            body(player, "warehouse.help"),
            if (materials.isEmpty()) body(player, "stock.empty") else table(materials.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { material ->
                materialName(player, material) to warehouseAmount(player, own[material] ?: 0, reserve[material] ?: 0)
            }),
            if (reserve.isNotEmpty()) body(player, "warehouse.reserve-help") else null,
            if (building.control == null) body(player, "reason.control-missing") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            if (!busy && building.control != null) {
                add(button(player, "intake", "warehouse.intake", STOCK) { openIntake(player, buildingId) })
                add(button(player, "deposit", "warehouse.deposit", COMMIT) {
                    perform(player, "civs.warehouse", "warehouse.title", { host.depositMaterials(player, buildingId) },
                        success = { showWarehouse(player, buildingId, current) }, failure = { showWarehouse(player, buildingId, current, it) })
                })
                if (materials.isNotEmpty()) add(button(player, "withdraw", "warehouse.withdraw", STOCK) { showWithdraw(player, buildingId) })
            }
            pages(player, current, materials.size) { showWarehouse(player, buildingId, it) }
            add(button(player, "refresh", "common.refresh") { showWarehouse(player, buildingId, current) })
        }, reopen = { showWarehouse(player, buildingId, current) })
    }

    private fun showWithdraw(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val inventory = city.inventory ?: return showStock(player)
        val own = inventory.buildingStock(buildingId)
        val reserve = inventory.materials
        val materials = (own.keys + reserve.keys).sorted()
        val current = page.coerceIn(0, lastPage(materials.size))
        present(player, "civs.withdraw", "warehouse.withdraw-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)), body(player, "warehouse.withdraw-help"),
            if (materials.isEmpty()) body(player, "stock.empty") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            if (!host.cityBusy(player) && building.control != null) materials.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, material ->
                val count = (own[material] ?: 0) + (reserve[material] ?: 0)
                add(valueButton(player, "withdraw_$index", "common.choice",
                    tr(player, "warehouse.material", "material" to materialName(player, material), "amount" to minOf(64L, count)), STOCK,
                    warehouseAmount(player, own[material] ?: 0, reserve[material] ?: 0)) {
                    perform(player, "civs.withdraw", "warehouse.withdraw-title", { host.withdrawMaterial(player, buildingId, material) },
                        success = { showWarehouse(player, buildingId) }, failure = { showWithdraw(player, buildingId, current, it) })
                })
            }
            pages(player, current, materials.size) { showWithdraw(player, buildingId, it) }
            add(button(player, "refresh", "common.refresh") { showWithdraw(player, buildingId, current) })
        }, reopen = { showWithdraw(player, buildingId, current) })
    }

    private fun openIntake(player: Player, buildingId: UUID) {
        val epoch = tasks.token()
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        val future = try { host.openBuildingInventory(player, buildingId) } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, epoch) { _, error ->
            if (error != null && !closed && player.isOnline && player.uniqueId !in visits) {
                runtime.beginFlow(player)
                roots[player.uniqueId] = "civs.warehouse"
                showWarehouse(player, buildingId, error = text.failure(player, error))
            }
        }
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
            if (city.buildings.size < CivsRules.MAX_BUILDINGS) add(button(player, "catalog", "buildings.catalog", PERSONAL) { showCatalog(player) })
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
        val eligible = city.buildings.size < CivsRules.MAX_BUILDINGS && !host.cityBusy(player)
        present(player, "civs.offer", "buildings.offer-title", listOfNotNull(
            body(buildingName(player, type, 1)), body(player, "building-purpose.${type.name.lowercase(Locale.ROOT)}"),
            body(player, "buildings.site-help"),
            if (city.buildings.size >= CivsRules.MAX_BUILDINGS) body(player, "reason.building-limit") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "prepare", "buildings.prepare", COMMIT) {
                beginSelection(player, { host.beginBuildingSelection(player, type) }) { showOffer(player, type, it) }
            })
            if (host.cityBusy(player)) add(button(player, "refresh", "common.refresh") { showOffer(player, type) })
        }, reopen = { showOffer(player, type) })
    }

    private fun showBuilding(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val assigned = city.workers.count { it.assignment == buildingId }
        present(player, "civs.building", "buildings.detail-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            body(player, "building-purpose.${building.type.name.lowercase(Locale.ROOT)}"),
            table(buildList {
                add(tr(player, "label.position") to coordinates(player, building.control ?: building.anchor))
                add(tr(player, "label.state") to tr(player, "building-phase.${building.phase.name.lowercase(Locale.ROOT)}"))
                add(tr(player, "label.workers") to tr(player, "common.fraction", "value" to assigned, "limit" to CivsRules.slots(city, building)))
                CivsProduction.material(building)?.let { material ->
                    add(tr(player, "label.output") to tr(player, "buildings.output", "amount" to CivsProduction.amount(building), "resource" to materialName(player, material)))
                }
                if (city.construction?.buildingId == buildingId) add(tr(player, "label.construction") to constructionText(player, city))
            }), body(player, "buildings.control-help"),
            if (building.type == BuildingType.TOWN_HALL && CivsRules.legacyHallBuilder(city)) body(player, "buildings.legacy-builder-help") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            add(button(player, "warehouse", "warehouse.open", STOCK) { showWarehouse(player, buildingId) })
            if (building.phase == BuildingPhase.SITE) add(button(player, "materials", "materials.open", COMMIT) { loadBuildingMaterials(player, buildingId) })
            if (CivsRules.slots(city, building) > 0) add(button(player, "workers", "buildings.workers", PEOPLE) { showBuildingWorkers(player, buildingId) })
            if (CivsProduction.options(building.type).isNotEmpty()) add(button(player, "production", "production.open", PERSONAL) { showProduction(player, buildingId) })
            if (building.level < 3 && building.phase == BuildingPhase.ACTIVE) add(button(player, "upgrade", "upgrades.open", PERSONAL) { showUpgrade(player, building.id) })
            add(button(player, "refresh", "common.refresh") { showBuilding(player, buildingId) })
        }, reopen = { showBuilding(player, buildingId) })
    }

    private fun loadBuildingMaterials(player: Player, buildingId: UUID) {
        val visit = present(player, "civs.building-materials", "materials.title", listOf(body(player, "materials.loading")), emptyList()) ?: return
        val future = try { host.buildingMaterials(player, buildingId) } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { materials, error ->
            if (!current(player, visit)) return@whenCompleteSync
            if (error == null) showBuildingMaterials(player, buildingId, requireNotNull(materials))
            else showBuilding(player, buildingId, text.failure(player, error))
        }
    }

    private fun showBuildingMaterials(player: Player, buildingId: UUID, estimate: Map<String, Long>, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (building.phase != BuildingPhase.SITE) return showBuilding(player, buildingId)
        val available = runCatching { host.availableMaterials(player) }
        val entries = estimate.toSortedMap().entries.toList()
        val current = page.coerceIn(0, lastPage(entries.size))
        present(player, "civs.building-materials", "materials.title", listOfNotNull(
            body(buildingName(player, building.type, building.level)), body(player, "materials.estimate-help"),
            if (entries.isNotEmpty()) table(entries.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { (material, count) ->
                materialName(player, material) to tr(player, "materials.available-needed", "have" to (available.getOrNull()?.get(material) ?: 0), "need" to count)
            }) else body(player, "materials.no-cost"),
            body(player, if (CivsRules.legacyHallBuilder(city)) "materials.legacy-start-help" else "materials.start-help"),
            if (city.construction != null) body(player, "reason.construction-busy") else null,
            available.exceptionOrNull()?.let { body(text.failure(player, it)) }, busyBody(player), error?.let(::body),
        ), buildList {
            if (city.construction == null && !host.cityBusy(player) && available.isSuccess) add(button(player, "start", "materials.start", COMMIT) {
                perform(player, "civs.building-materials", "materials.title", { host.startBuilding(player, buildingId) },
                    success = { showBuilding(player, buildingId) }, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) })
            })
            pages(player, current, entries.size) { showBuildingMaterials(player, buildingId, estimate, it) }
            add(button(player, "refresh", "common.refresh") { loadBuildingMaterials(player, buildingId) })
        }, reopen = { showBuildingMaterials(player, buildingId, estimate, current) })
    }

    private fun showBuildingWorkers(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val slots = CivsRules.slots(city, building)
        val assigned = city.workers.count { it.assignment == buildingId }
        val workers = city.workers.filter { assigned < slots || it.assignment == buildingId }.sortedBy { it.assignment != buildingId }
        val current = page.coerceIn(0, lastPage(workers.size))
        present(player, "civs.building-workers", "workers.building-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(listOf(tr(player, "label.workers") to tr(player, "common.fraction", "value" to assigned, "limit" to slots))),
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
            add(button(player, "refresh", "common.refresh") { showBuildingWorkers(player, buildingId, current) })
        }, reopen = { showBuildingWorkers(player, buildingId, current) })
    }

    private fun showProduction(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val options = CivsProduction.options(building.type)
        val selected = CivsProduction.material(building)
        val locked = host.cityBusy(player) || city.construction?.buildingId == buildingId
        present(player, "civs.production", "production.title", listOfNotNull(
            body(buildingName(player, building.type, building.level)), body(player, "production.help"),
            if (city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            if (!locked) options.forEachIndexed { index, material ->
                add(valueButton(player, "production_$index", if (material == selected) "common.selected" else "common.choice",
                    tr(player, "buildings.output", "resource" to materialName(player, material), "amount" to CivsProduction.amount(building.copy(production = material))),
                    if (material == selected) COMMIT else WHITE) {
                    if (material == selected) showProduction(player, buildingId)
                    else perform(player, "civs.production", "production.title", { host.setProduction(player, buildingId, material) },
                        success = { showProduction(player, buildingId) }, failure = { showProduction(player, buildingId, it) })
                })
            }
            add(button(player, "refresh", "common.refresh") { showProduction(player, buildingId) })
        }, reopen = { showProduction(player, buildingId) })
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
        val hallRequired = building.type != BuildingType.TOWN_HALL && target > city.level()
        val eligible = !hallRequired && city.construction == null && !host.cityBusy(player) && building.phase == BuildingPhase.ACTIVE
        present(player, "civs.upgrade", "upgrades.detail-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(buildList {
                add(tr(player, "label.level") to tr(player, "common.change", "before" to building.level, "after" to target))
                add(tr(player, "label.benefit") to upgradeBenefit(player, building))
                add(tr(player, "warehouse.capacity") to tr(player, "common.change", "before" to CivInventory.buildingCapacity(building),
                    "after" to CivInventory.buildingCapacity(building.copy(level = target))))
                if (building.type == BuildingType.TOWN_HALL) add(tr(player, "label.radius") to tr(player, "common.change", "before" to city.radius, "after" to city.radius.toLong() + 32))
            }), body(player, "upgrades.materials-help"),
            when {
                city.construction != null -> body(player, "reason.construction-busy")
                hallRequired -> body(tr(player, "upgrades.hall-required", "level" to target))
                else -> null
            }, busyBody(player), error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "prepare", "upgrades.prepare", COMMIT) {
                perform(player, "civs.preview", "preview.title", { host.prepareUpgrade(player, buildingId) },
                    success = { resumePlacement(player) }, failure = { showUpgrade(player, buildingId, it) },
                    cancelPending = { host.cancelPreview(player) })
            })
            if (host.cityBusy(player)) add(button(player, "refresh", "common.refresh") { showUpgrade(player, buildingId) })
        }, reopen = { showUpgrade(player, buildingId) })
    }

    private fun showWorkers(player: Player, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val current = page.coerceIn(0, lastPage(city.workers.size))
        val limit = minOf(CivsRules.populationLimit(city), CivsRules.beds(city))
        present(player, "civs.workers", "workers.title", listOfNotNull(
            body(player, "workers.help"), table(listOf(
                tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to limit),
                tr(player, "label.unassigned") to text.literal(city.workers.count { it.assignment == null }),
            )), if ((city.immigration?.remaining ?: 0) > 0) body(player, "workers.arrival-help") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            city.workers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, worker ->
                add(valueButton(player, "worker_$index", "common.destination", text.literal(worker.name), PEOPLE,
                    workerStatus(player, worker)) { showWorker(player, worker.id) })
            }
            if (city.workers.size < limit && (city.immigration?.remaining ?: 0) == 0) add(button(player, "recruit", "workers.recruit", COMMIT) { showRecruit(player) })
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
            )), busyBody(player), error?.let(::body),
        ), buildList {
            if (!host.cityBusy(player)) add(button(player, "assign", "workers.assign", PERSONAL) { showAssignments(player, workerId) })
            if (worker.assignment != null && !host.cityBusy(player)) add(button(player, "unassign", "workers.unassign") {
                perform(player, "civs.worker", "workers.detail-title", { host.assign(player, workerId, null) },
                    success = { showWorker(player, workerId) }, failure = { showWorker(player, workerId, it) })
            })
            if (host.cityBusy(player)) add(button(player, "refresh", "common.refresh") { showWorker(player, workerId) })
        }, reopen = { showWorker(player, workerId) })
    }

    private fun showAssignments(player: Player, workerId: UUID, page: Int = 0, error: Component? = null) {
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
            if (host.cityBusy(player)) add(button(player, "refresh", "common.refresh") { showAssignments(player, workerId, current) })
        }, reopen = { showAssignments(player, workerId, current) })
    }

    private fun showRecruit(player: Player, error: Component? = null) {
        val city = city(player) ?: return
        val available = if (city.inventory == null) Result.success(mapOf("BREAD" to city.stock.food)) else runCatching { host.availableMaterials(player) }
        val bread = available.getOrNull()?.get("BREAD") ?: 0
        val room = city.workers.size < minOf(CivsRules.beds(city), CivsRules.populationLimit(city))
        val arriving = (city.immigration?.remaining ?: 0) > 0
        val eligible = room && bread >= CivsRules.RECRUIT_FOOD && !arriving && !host.cityBusy(player) && available.isSuccess
        present(player, "civs.recruit", "workers.recruit-title", listOfNotNull(
            body(player, "workers.recruit-help"), table(listOf(materialName(player, "BREAD") to tr(player, "common.required", "have" to bread, "need" to CivsRules.RECRUIT_FOOD))),
            when {
                arriving -> body(player, "workers.arrival-help")
                !room -> body(player, "reason.population-limit")
                available.isSuccess && bread < CivsRules.RECRUIT_FOOD -> body(player, "workers.recruit-shortage")
                else -> null
            }, available.exceptionOrNull()?.let { body(text.failure(player, it)) }, busyBody(player), error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "confirm", "workers.recruit-confirm", COMMIT) {
                perform(player, "civs.recruit", "workers.recruit-title", { host.recruit(player) },
                    success = { showWorkers(player) }, failure = { showRecruit(player, it) })
            })
            if (!eligible) add(button(player, "refresh", "common.refresh") { showRecruit(player) })
        }, reopen = { showRecruit(player) })
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
            city.construction != null && city.workers.none { worker -> worker.assignment?.let(city::building)?.let(CivsRules::isBuilder) == true } ->
                if (CivsRules.legacyHallBuilder(city)) "city.next-legacy-builder" else "city.next-builder"
            city.construction != null -> "city.next-building"
            city.workers.isEmpty() && (city.immigration?.remaining ?: 0) > 0 -> "city.next-arrival"
            city.buildings.none { it.type == BuildingType.BUILDER_HUT } && !CivsRules.legacyHallBuilder(city) -> "city.next-builder-hut"
            city.buildings.any { it.phase == BuildingPhase.SITE } -> "city.next-start"
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
            worker.assignment == null && status in setOf("working", "building", "delivering", "to-store", "to-work", "moving-to-work", "moving-to-storage") -> "unassigned"
            worker.assignment != null && (status == "unassigned" || status == "no-assignment") -> "waiting"
            else -> status
        })
    }

    private fun upgradeBenefit(player: Player, building: CivBuilding): Component = when (building.type) {
        BuildingType.TOWN_HALL -> tr(player, "upgrades.population", "before" to building.level * 4, "after" to (building.level + 1) * 4)
        BuildingType.HOUSE -> tr(player, "upgrades.beds", "before" to building.level * 2, "after" to (building.level + 1) * 2)
        BuildingType.WAREHOUSE -> tr(player, "upgrades.warehouse")
        BuildingType.BUILDER_HUT -> tr(player, "upgrades.builder")
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
            showEntry(player)
            return null
        }
        return city
    }

    private fun beginSelection(player: Player, action: () -> CompletableFuture<Unit>, failure: (Component) -> Unit) {
        val visit = visits[player.uniqueId] ?: return
        visit.cancelPending = { host.cancelSelection(player, "dialog-closed") }
        val future = try { action() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { _, error ->
            if (!current(player, visit)) return@whenCompleteSync
            visit.cancelPending = null
            if (error == null) resumePlacement(player) else failure(text.failure(player, error))
        }
    }

    private fun perform(player: Player, id: String, title: String, action: () -> CompletableFuture<Unit>,
        success: () -> Unit, failure: (Component) -> Unit, cancelPending: (() -> Unit)? = null,
        workingKey: String = "common.working") {
        val visit = present(player, id, title, listOf(body(player, workingKey)), emptyList()) ?: return
        visit.cancelPending = cancelPending
        val future = try { action() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { _, error ->
            if (!current(player, visit)) {
                CivsDiagnostics.event("ui_completion_ignored", "player" to player.uniqueId, "screen" to id, "visit" to visit.id)
                return@whenCompleteSync
            }
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
        CivsDiagnostics.event("ui_screen", *CivsDiagnostics.actor(player), "screen" to id, "visit" to visit.id,
            "actions" to buttons.joinToString { it.id.toString() }, "inputs" to inputs.size)
        val closeOnEscape = player.getMetadata("arc-menu-escape").any { it.asString() == "close" }
        val footerKey = if (closeOnEscape || roots[player.uniqueId] == id) "common.close" else "common.back"
        val guarded = buttons.map { button -> button.copy(closeDialogBeforeAction = false, onClick = { context ->
            val accepted = current(player, visit) && !visit.consumed
            CivsDiagnostics.event("ui_click", *CivsDiagnostics.actor(player), "screen" to id, "visit" to visit.id,
                "action" to button.id.toString(), "outcome" to if (accepted) "accepted" else "ignored",
                "reason" to if (accepted) null else if (visit.consumed) "already-consumed" else "stale-visit",
                "city_name" to context.text(NAME), "member_name" to context.text(MEMBER))
            if (accepted) {
                visit.consumed = true
                if (button.closeDialogBeforeAction) runtime.close(player)
                button.onClick.handle(context)
            }
        }) }
        runtime.open(player, PaperDialogScreen(id = id, title = tr(player, title).color(LAND), body = bodies,
            inputs = inputs, buttons = guarded, columns = if (guarded.size == 1) 1 else 2,
            exitButton = button(player, "history", footerKey).copy(width = 200),
        ), reopen, {
            CivsDiagnostics.event("ui_dismiss", "player" to player.uniqueId, "screen" to id, "visit" to visit.id,
                "current" to (visits[player.uniqueId] === visit), "pending" to (visit.cancelPending != null))
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
    private fun busyBody(player: Player): PaperDialogBody? = if (host.cityBusy(player)) body(player, "reason.city-busy") else null
    private fun materialName(player: Player, material: String): Component = text.material(player, material)
    private fun warehouseAmount(player: Player, own: Long, reserve: Long): Component = if (reserve > 0)
        tr(player, "warehouse.amount-with-reserve", "own" to own, "reserve" to reserve) else text.literal(own)
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
