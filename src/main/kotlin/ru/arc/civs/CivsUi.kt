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
    private val equipment = CivsEquipmentMenu(plugin, tasks, text)

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
        equipment.dismiss(player)
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
                emptyList(), reopen = { open(player) })
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
        if (host.selection(player) != null) player.sendActionBar(text.info(player, "selection.hint"))
        else if (host.preview(player) != null) player.sendMessage(text.info(player, "panel.opened"))
    }

    fun openWorker(player: Player, cityId: UUID, workerId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
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
        equipment.dismiss(player)
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
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showEntry(player) }) }

    private fun showRemote(player: Player, feedback: Component? = null) {
        val remote = host.remoteCity(player) ?: return showEntry(player)
        val city = remote.city
        present(player, "civs.remote", "city.title", listOfNotNull(
            body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
            body(tr(player, "network.remote-help", "server" to remote.server.value)),
            table(listOf(
                tr(player, "network.server-label") to text.literal(remote.server.value),
                tr(player, "label.level") to text.literal(city.level()),
                tr(player, "label.radius") to tr(player, "city.radius", "radius" to city.radius),
                tr(player, "label.population") to tr(player, "common.fraction", "value" to city.workers.size, "limit" to CivsRules.populationLimit(city)),
                tr(player, "label.buildings") to text.literal(city.buildings.size),
                tr(player, "label.construction") to constructionText(player, city),
            )),
            body(player, "network.snapshot-help"), feedback?.let(::body),
        ), listOf(
            valueButton(player, "transfer", "network.transfer", text.literal(remote.server.value), NAVIGATION) {
                perform(player, "civs.remote", "city.title", { host.transferHome(player) },
                    success = { showRemote(player, tr(player, "network.transfer-requested", "server" to remote.server.value)) },
                    failure = { showRemote(player, it) }, workingKey = "network.connecting")
            },
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
                tr(player, "label.control") to coordinates(player, preview.control),
                tr(player, "label.entrance") to coordinates(player, preview.entrance),
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
            if (host.city(player)?.canManage(player.uniqueId) == true) {
                add(button(player, "management", "management.open", PERSONAL) { showManagement(player) })
            }
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
            body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
            table(listOf(
                tr(player, "label.level") to tint(text.literal(city.level()), PERSONAL),
                tr(player, "label.radius") to tint(tr(player, "city.radius", "radius" to city.radius), NAVIGATION),
                tr(player, "label.population") to tint(tr(player, "common.fraction", "value" to city.workers.size,
                    "limit" to CivsRules.populationLimit(city)), PEOPLE),
                tr(player, "label.beds") to tint(text.literal(CivsRules.beds(city)), PEOPLE),
                tr(player, "label.buildings") to tint(tr(player, "common.fraction", "value" to city.buildings.size,
                    "limit" to CivsRules.MAX_BUILDINGS), LAND),
                tr(player, "label.activity") to tint(tr(player, if (sleep) "city.sleeping" else "city.active"),
                    if (sleep) PERSONAL else COMMIT),
                tr(player, "label.construction") to constructionText(player, city),
            )),
            construction?.failure?.let { body(text.reason(player, it)) },
            body(tint(nextStep(player, city), STOCK)), busyBody(player), feedback?.let(::body),
        ), buildList {
            add(button(player, "management", "management.open", PERSONAL) { showManagement(player) })
            add(button(player, "buildings", "buildings.open", LAND) { showBuildings(player) })
            add(button(player, "workers", "workers.open", PEOPLE) { showWorkers(player) })
            add(button(player, "guide", "guide.open", NAVIGATION) { showGuide(player) })
        }, reopen = { showOverview(player) })
    }

    private fun showGuide(player: Player) {
        city(player) ?: return
        present(player, "civs.guide", "guide.title", listOf(
            body(player, "city.boundary-help"), body(player, "city.sleep-rule"), body(player, "buildings.site-help"),
            body(player, "guide.population-help"), body(player, "guide.storage-help"),
        ), emptyList(), reopen = { showGuide(player) })
    }

    private fun showManagement(player: Player) {
        val city = city(player) ?: return
        present(player, "civs.management", "management.title", listOfNotNull(
            body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
            body(player, "management.help"),
            city.inventory?.let { inventory -> table(listOf(
                tr(player, "stock.mode-label") to tr(player, "stock.mode.${inventory.mode.name.lowercase(Locale.ROOT)}"),
            )) },
            city.inventory?.let { body(player, "stock.${it.mode.name.lowercase(Locale.ROOT)}-help") },
            busyBody(player),
        ), buildList {
            add(button(player, "stock", "stock.open", STOCK) { showStock(player) })
            add(button(player, "upgrades", "upgrades.open", PERSONAL) { showUpgrades(player) })
            add(button(player, "members", "members.open", PEOPLE) { showMembers(player) })
            if (city.owner == player.uniqueId) add(button(player, "delete_city", "delete.open", DANGER) { showDeleteCity(player, city.id) })
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showManagement(player) })
    }

    private fun showDeleteCity(player: Player, expectedCityId: UUID, initial: String = "", error: Component? = null) {
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
            if (!busy) add(button(player, "delete", "delete.confirm", DANGER) { context ->
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
        ), reopen = { showDeleteCity(player, expectedCityId, draft) })
    }

    private fun showStock(player: Player, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val inventory = city.inventory
        if (inventory == null) {
            notice(player, "civs.stock", "stock.title", text.reason(player, "materials-migration-pending")) { showOverview(player) }
            return
        }
        val current = page.coerceIn(0, lastPage(city.buildings.size, BUILDINGS_PAGE_SIZE))
        val physical = inventory.mode == CivStorageMode.PHYSICAL
        val pending = inventory.materials.values.sum() + inventory.buildingMaterials.orEmpty().values.sumOf { it.values.sum() }
        val busy = host.cityBusy(player)
        present(player, "civs.stock", "stock.title", listOfNotNull(
            body(player, if (physical) "stock.physical-help" else "stock.help"), table(buildList {
                add(tr(player, "stock.mode-label") to tr(player, "stock.mode.${inventory.mode.name.lowercase(Locale.ROOT)}"))
                if (!physical) add(tr(player, "stock.reserve-label") to tr(player, "stock.items", "amount" to inventory.materials.values.sum()))
                else if (pending > 0) add(tr(player, "stock.pending-label") to tr(player, "stock.items", "amount" to pending))
            }), if (!physical) body(player, "stock.reserve-help") else null,
            if (physical && pending > 0) body(player, "stock.pending-help") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            add(button(player, "materials", "stock.available", STOCK) { showAvailableMaterials(player) })
            add(button(player, "mode", "stock.mode-open", PERSONAL) { showStorageMode(player) })
            add(button(player, "containers", "stock.containers-open", STOCK) { showContainers(player) })
            if (physical && pending > 0 && !busy) add(button(player, "materialize", "stock.materialize", COMMIT) {
                perform(player, "civs.stock", "stock.title", { host.materializeStorage(player) },
                    success = { showStock(player, current) }, failure = { showStock(player, current, it) })
            })
            if (!physical || !busy) city.buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
                add(valueButton(player, "warehouse_$index", "common.destination", buildingName(player, building.type, building.level), STOCK) {
                    openWarehouse(player, building.id)
                })
            }
            pages(player, current, city.buildings.size) { showStock(player, it) }
        }, reopen = { showStock(player, current) })
    }

    private fun showAvailableMaterials(player: Player, page: Int = 0) {
        val city = city(player) ?: return
        val result = runCatching { host.availableMaterials(player).toSortedMap().entries.toList() }
        val materials = result.getOrDefault(emptyList())
        val current = page.coerceIn(0, lastPage(materials.size))
        present(player, "civs.materials", "stock.available-title", listOfNotNull(
            body(player, if (city.inventory?.mode == CivStorageMode.PHYSICAL) "stock.available-physical-help" else "stock.available-help"),
            if (materials.isEmpty() && result.isSuccess) body(player, "stock.empty") else null,
            if (materials.isNotEmpty()) table(materials.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { (material, count) ->
                materialName(player, material) to text.literal(count)
            }) else null,
            result.exceptionOrNull()?.let { body(text.failure(player, it)) }, busyBody(player),
        ), buildList {
            pages(player, current, materials.size) { showAvailableMaterials(player, it) }
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
        }, reopen = { showContainer(player, position) })
    }

    private fun openWarehouse(player: Player, buildingId: UUID) {
        val city = city(player) ?: return
        if (city.inventory?.mode != CivStorageMode.PHYSICAL) return showWarehouse(player, buildingId)
        if (host.cityBusy(player)) return showBuilding(player, buildingId, text.reason(player, "city-busy"))
        openIntake(player, buildingId)
    }

    private fun showWarehouse(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val inventory = city.inventory ?: return showStock(player)
        if (inventory.mode != CivStorageMode.VIRTUAL) return showBuilding(player, buildingId, error)
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
        }, reopen = { showWarehouse(player, buildingId, current) })
    }

    private fun showWithdraw(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val inventory = city.inventory ?: return showStock(player)
        if (inventory.mode != CivStorageMode.VIRTUAL) return showBuilding(player, buildingId, error)
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
                if (host.city(player)?.inventory?.mode == CivStorageMode.PHYSICAL) {
                    roots[player.uniqueId] = "civs.building"
                    showBuilding(player, buildingId, text.failure(player, error))
                } else {
                    roots[player.uniqueId] = "civs.warehouse"
                    showWarehouse(player, buildingId, error = text.failure(player, error))
                }
            }
        }
    }

    private fun showBuildings(player: Player, page: Int = 0) {
        val city = city(player) ?: return
        val current = page.coerceIn(0, lastPage(city.buildings.size))
        val hasQueue = city.construction != null || city.constructionQueue.orEmpty().isNotEmpty()
        present(player, "civs.buildings", "buildings.title", listOfNotNull(
            if (hasQueue) null else body(player, "buildings.help"), body(constructionText(player, city)),
        ), buildList {
            if (hasQueue) {
                add(button(player, "queue", "queue.open", LAND) { showQueue(player) })
            }
            city.buildings.drop(current * BUILDINGS_PAGE_SIZE).take(BUILDINGS_PAGE_SIZE).forEachIndexed { index, building ->
                add(valueButton(player, "building_$index", "common.destination", buildingName(player, building.type, building.level), buildingColor(building.type),
                    buildingStatus(player, city, building)) {
                    showBuilding(player, building.id)
                })
            }
            if (city.buildings.size < CivsRules.MAX_BUILDINGS) add(button(player, "catalog", "buildings.catalog", PERSONAL) { showCatalog(player) })
            pages(player, current, city.buildings.size, BUILDINGS_PAGE_SIZE) { showBuildings(player, it) }
        }, reopen = { showBuildings(player, current) })
    }

    private fun showQueue(player: Player, page: Int = 0) {
        val city = city(player) ?: return
        val queued = city.constructionQueue.orEmpty()
        val current = page.coerceIn(0, lastPage(queued.size))
        present(player, "civs.queue", "queue.title", listOfNotNull(
            city.construction?.let { table(listOf(tr(player, "queue.active") to constructionText(player, city))) }
                ?: body(player, "queue.no-active"),
            body(tr(player, if (queued.isEmpty()) "queue.empty" else "queue.waiting", "count" to queued.size)),
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
        }, reopen = { showQueue(player, current) })
    }

    private fun showCatalog(player: Player, page: Int = 0) {
        city(player) ?: return
        val types = BuildingType.entries.filter { it != BuildingType.TOWN_HALL }
        val current = page.coerceIn(0, lastPage(types.size, BUILDINGS_PAGE_SIZE))
        present(player, "civs.catalog", "buildings.catalog-title", listOf(body(player, "buildings.catalog-help")),
            buildList {
                types.drop(current * BUILDINGS_PAGE_SIZE).take(BUILDINGS_PAGE_SIZE).forEachIndexed { index, type ->
                    add(valueButton(player, "offer_$index", "common.destination", text.building(player, type), buildingColor(type)) { showOffer(player, type) })
                }
                pages(player, current, types.size, BUILDINGS_PAGE_SIZE) { showCatalog(player, it) }
            }, reopen = { if (city(player) != null) showCatalog(player, current) })
    }

    private fun showOffer(player: Player, type: BuildingType, error: Component? = null) {
        val city = city(player) ?: return
        val eligible = city.buildings.size < CivsRules.MAX_BUILDINGS
        present(player, "civs.offer", "buildings.offer-title", listOfNotNull(
            body(buildingName(player, type, 1)), body(player, "building-purpose.${type.name.lowercase(Locale.ROOT)}"),
            if (type in setOf(BuildingType.BUILDER_HUT, BuildingType.WAREHOUSE) || CivsProduction.options(type).isNotEmpty())
                body(player, "buildings.workplaces-levels") else null,
            body(player, "buildings.site-help"),
            if (city.buildings.size >= CivsRules.MAX_BUILDINGS) body(player, "reason.building-limit") else null,
            error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "prepare", "buildings.prepare", COMMIT) {
                beginSelection(player, { host.beginBuildingSelection(player, type) }) { showOffer(player, type, it) }
            })
        }, reopen = { showOffer(player, type) })
    }

    private fun showBuilding(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val assigned = city.workers.count { it.assignment == buildingId }
        val request = host.buildingRequest(player, buildingId)
        val requestTool = requestTool(request)
        val busy = host.cityBusy(player)
        val queued = queuePosition(city, buildingId) != null
        val legacyControl = building.phase == BuildingPhase.ACTIVE && (building.control == null || building.control == building.anchor)
        present(player, "civs.building", "buildings.detail-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            body(player, if (building.type == BuildingType.TOWN_HALL && CivsRules.isBuilder(city, building))
                "buildings.legacy-builder-help" else "building-purpose.${building.type.name.lowercase(Locale.ROOT)}"),
            table(buildList {
                add(tr(player, "label.control") to (building.control?.let { coordinates(player, it) } ?: tr(player, "control.unset")))
                add(tr(player, "label.state") to buildingStatus(player, city, building))
                add(tr(player, "label.workers") to tr(player, "common.fraction", "value" to assigned, "limit" to CivsRules.slots(city, building)))
                CivsProduction.material(building)?.takeIf { !CivsRules.isBuilder(city, building) }?.let { material ->
                    if (building.type != BuildingType.QUARRY) add(tr(player, "label.output") to
                        if (building.type in setOf(BuildingType.FARM, BuildingType.FORESTER)) materialName(player, material)
                        else tr(player, "buildings.output", "amount" to CivsProduction.amount(building), "resource" to materialName(player, material)))
                }
                city.construction?.takeIf { it.buildingId == buildingId }?.let { order ->
                    add(tr(player, "label.construction") to tr(player, "common.fraction", "value" to order.workDone, "limit" to order.workRequired))
                }
            }), body(player, "buildings.control-help"),
            if (legacyControl) body(player, "control.legacy-help") else null,
            if (legacyControl && city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
            request?.let { body(tint(if (requestTool != null) tr(player, "workers.tool-request-$requestTool")
                else tr(player, "buildings.request", "item" to materialName(player, it.material.name), "amount" to it.amount), STOCK)) },
            error?.let(::body),
        ), buildList {
            if (assigned > 0 && !busy) add(button(player, "summon", "buildings.summon", NAVIGATION) {
                perform(player, "civs.building", "buildings.detail-title", { host.summonWorker(player, buildingId) },
                    success = { showBuilding(player, buildingId) }, failure = { showBuilding(player, buildingId, it) })
            }.copy(tooltip = tr(player, "buildings.summon-help")))
            if (request != null) {
                add(requestButton(player, request, "buildings.request-unavailable", "buildings.give-help", give = {
                    perform(player, "civs.building", "buildings.detail-title",
                        { host.giveBuildingRequest(player, buildingId, request.material.name) },
                        success = { showBuilding(player, buildingId) }, failure = { showBuilding(player, buildingId, it) })
                }, refresh = { showBuilding(player, buildingId) }))
            }
            if (city.inventory?.mode != CivStorageMode.PHYSICAL || !host.cityBusy(player)) {
                add(button(player, "warehouse", "warehouse.open", STOCK) { openWarehouse(player, buildingId) })
            }
            if (building.phase == BuildingPhase.SITE || city.construction?.buildingId == buildingId || queued) {
                add(button(player, "materials", "materials.open", COMMIT) { loadBuildingMaterials(player, buildingId) })
            }
            if (queued) add(button(player, "queue", "queue.open", LAND) { showQueue(player) })
            if (CivsRules.slots(city, building) > 0) add(button(player, "workers", "buildings.workers", PEOPLE) { showBuildingWorkers(player, buildingId) })
            if (building.type == BuildingType.WAREHOUSE && city.inventory != null)
                add(button(player, "logistics", "logistics.open", STOCK) { showSupplyRequests(player, buildingId) })
            if (CivsProduction.options(building.type).isNotEmpty() && !CivsRules.isBuilder(city, building) && building.type != BuildingType.QUARRY)
                add(button(player, "production", "production.open", PERSONAL) { showProduction(player, buildingId) })
            if (building.level < 3 && building.phase == BuildingPhase.ACTIVE && !queued && city.construction?.buildingId != building.id)
                add(button(player, "upgrade", "upgrades.open", PERSONAL) { showUpgrade(player, building.id) })
            if (legacyControl && !host.cityBusy(player) && city.construction?.buildingId != buildingId) {
                add(button(player, "relocate_control", "control.open", NAVIGATION) { loadControlRelocation(player, buildingId) })
            }
            if (building.type != BuildingType.TOWN_HALL && !host.cityBusy(player)) {
                add(button(player, "delete_building", "delete-building.open", DANGER) { showDeleteBuilding(player, city.id, buildingId) })
            }
        }, reopen = { showBuilding(player, buildingId) })
    }

    private fun showDeleteBuilding(player: Player, expectedCityId: UUID, buildingId: UUID, error: Component? = null) {
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
            if (!host.cityBusy(player)) add(button(player, "delete", "delete-building.confirm", DANGER) {
                perform(player, "civs.delete-building", "delete-building.title",
                    { host.deleteBuilding(player, expectedCityId, buildingId) }, success = {
                        runtime.beginFlow(player)
                        roots[player.uniqueId] = "civs.buildings"
                        showBuildings(player)
                        player.sendMessage(text.success(player, "delete-building.success", "building" to text.building(player, building.type)))
                    }, failure = { showDeleteBuilding(player, expectedCityId, buildingId, it) })
            })
        }, reopen = { showDeleteBuilding(player, expectedCityId, buildingId) })
    }

    private fun loadControlRelocation(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (building.phase != BuildingPhase.ACTIVE) return showBuilding(player, buildingId, text.reason(player, "control-relocation-site"))
        val visit = present(player, "civs.control-relocation", "control.title", listOf(body(player, "control.loading")), emptyList()) ?: return
        val future = try { host.buildingControl(player, buildingId) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        future.whenCompleteSync(tasks, visit.epoch) { target, failure ->
            if (!current(player, visit)) return@whenCompleteSync
            if (host.city(player)?.id != city.id) showEntry(player)
            else if (failure != null) showBuilding(player, buildingId, text.failure(player, failure).color(DANGER))
            else showControlRelocation(player, buildingId, requireNotNull(target), error)
        }
    }

    private fun showControlRelocation(player: Player, buildingId: UUID, target: CivPosition, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (building.phase != BuildingPhase.ACTIVE || building.control == target) return showBuilding(player, buildingId, error)
        val busy = host.cityBusy(player)
        present(player, "civs.control-relocation", "control.title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(listOf(
                tr(player, "control.old-position") to (building.control?.let { coordinates(player, it) } ?: tr(player, "control.unset")),
                tr(player, "control.new-position") to coordinates(player, target),
            )), body(player, "control.help"), body(player, "control.retained-help"),
            if (city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
            busyBody(player), error?.let { body(it.color(DANGER)) },
        ), buildList {
            if (!busy && city.construction?.buildingId != buildingId) add(button(player, "relocate", "control.confirm", COMMIT) {
                perform(player, "civs.control-relocation", "control.title", { host.relocateControl(player, buildingId, target) },
                    success = { showBuilding(player, buildingId, tr(player, "control.success").color(COMMIT)) },
                    failure = { loadControlRelocation(player, buildingId, it) })
            })
        }, reopen = { loadControlRelocation(player, buildingId) })
    }

    private fun loadBuildingMaterials(player: Player, buildingId: UUID) {
        val city = city(player) ?: return
        city.construction?.takeIf { it.buildingId == buildingId }?.let {
            return showBuildingMaterials(player, buildingId, it.materials.orEmpty())
        }
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
        val order = city.construction?.takeIf { it.buildingId == buildingId }
        val queued = queuePosition(city, buildingId)
        if (building.phase != BuildingPhase.SITE && order == null && queued == null) return showBuilding(player, buildingId)
        val available = runCatching { host.localMaterials(player, buildingId) }
        val entries = (order?.materials ?: estimate).toSortedMap().entries.toList()
        val incremental = order?.payment != null
        val current = page.coerceIn(0, lastPage(entries.size, MATERIALS_PAGE_SIZE))
        val canSupply = !host.cityBusy(player) && available.isSuccess && (order == null || incremental) &&
            entries.any { (material, count) -> count > (available.getOrNull()?.get(material) ?: 0) }
        fun supplied(result: CivsSupplyResult) {
            var message = if (result.moved == 0L && result.remaining == 0L) tr(player, "materials.supplies-ready")
                else tr(player, "materials.supplied", "moved" to result.moved, "remaining" to result.remaining)
            if (result.storageFull) message = message.append(Component.space()).append(tr(player, "materials.storage-full"))
            showBuildingMaterials(player, buildingId, estimate, current, message.color(if (result.remaining > 0) STOCK else COMMIT))
        }
        present(player, "civs.building-materials", "materials.title", listOfNotNull(
            PaperDialogBody(buildingName(player, building.type, order?.targetLevel
                ?: if (queued != null && building.phase == BuildingPhase.ACTIVE) building.level + 1 else building.level).let { name ->
                if (order == null) name else name.append(Component.text(" · ${order.workDone}/${order.workRequired}"))
            }, 460),
            PaperDialogBody(error ?: tr(player, when {
                queued != null -> "queue.materials-help"
                order != null && !incremental -> "materials.prepaid-help"
                order?.phase == ConstructionPhase.WAITING_MATERIALS -> "materials.waiting-help"
                incremental -> "materials.remaining-help"
                else -> "materials.site-help"
            }, "position" to queued), 460),
            if (entries.isNotEmpty()) DialogTables.body(entries.drop(current * MATERIALS_PAGE_SIZE).take(MATERIALS_PAGE_SIZE).map { (material, count) ->
                val have = available.getOrNull()?.get(material) ?: 0
                materialName(player, material) to tint(tr(player, if (incremental) "materials.available-remaining" else "materials.available-needed",
                    "have" to have, "need" to count), if (have >= count) COMMIT else STOCK)
            }, tint(tr(player, "materials.item"), STOCK) to tint(tr(player, "materials.stock-needed"), STOCK),
                DialogTables.Frame.EPIC, 320, DialogTables.Columns.LABEL_WIDE) else body(player, "materials.no-cost"),
            available.exceptionOrNull()?.let { body(text.failure(player, it)) },
        ), buildList {
            if (canSupply) {
                add(button(player, "supply", "materials.supply", STOCK) {
                    perform(player, "civs.building-materials", "materials.title", { host.supplyConstruction(player, buildingId) },
                        success = ::supplied, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) },
                        workingKey = "materials.supplying")
                }.copy(tooltip = tr(player, "materials.supply-help")))
                if (isAdmin(player)) add(button(player, "grant", "materials.grant", PERSONAL) {
                    perform(player, "civs.building-materials", "materials.title", { host.grantConstructionMaterials(player, buildingId) },
                        success = ::supplied, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) },
                        workingKey = "materials.supplying")
                }.copy(tooltip = tr(player, "materials.grant-help")))
            }
            if (order == null && queued == null) add(button(player, "start",
                if (city.construction == null && city.constructionQueue.orEmpty().isEmpty()) "materials.start" else "queue.add", COMMIT) {
                perform(player, "civs.building-materials", "materials.title", { host.startBuilding(player, buildingId) },
                    success = { showBuilding(player, buildingId) }, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) },
                    workingKey = "materials.preparing")
            }.copy(tooltip = tr(player, if (CivsRules.legacyHallBuilder(city)) "materials.legacy-start-help" else "materials.start-help")))
            pages(player, current, entries.size, MATERIALS_PAGE_SIZE) { showBuildingMaterials(player, buildingId, estimate, it) }
        }, reopen = { showBuildingMaterials(player, buildingId, estimate, current) })
    }

    private fun showBuildingWorkers(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val slots = CivsRules.slots(city, building)
        val assigned = city.workers.count { it.assignment == buildingId }
        val workers = city.workers.filter { !CivsResidents.isChild(it) && (assigned < slots || it.assignment == buildingId) }
            .sortedBy { it.assignment != buildingId }
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
        }, reopen = { showBuildingWorkers(player, buildingId, current) })
    }

    private fun showProduction(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (CivsRules.isBuilder(city, building)) return showBuilding(player, buildingId)
        val options = CivsProduction.options(building.type)
        val selected = CivsProduction.material(building)
        val inputs = CivsProduction.inputs(building)
        val available = if (inputs.isNotEmpty()) runCatching { host.localMaterials(player, buildingId) } else null
        val locked = host.cityBusy(player) || city.construction?.buildingId == buildingId
        present(player, "civs.production", "production.title", listOfNotNull(
            body(buildingName(player, building.type, building.level)), body(player,
                when { building.type == BuildingType.FARM && inputs.isEmpty() -> "production.field-help"
                    building.type == BuildingType.FORESTER -> "production.forest-help"
                    inputs.isEmpty() -> "production.help"
                    building.type == BuildingType.TOWN_HALL -> "production.scribe-help"
                    else -> "production.processor-help" }),
            if (inputs.isNotEmpty()) table(inputs.map { (material, needed) -> materialName(player, material) to
                tr(player, "common.required", "have" to if (available?.isSuccess == true)
                    text.literal(available.getOrNull()?.get(material) ?: 0) else tr(player, "workers.stock-unknown"), "need" to needed) }) else null,
            if (inputs.isNotEmpty()) body(tr(player, "production.recipe-output", "item" to materialName(player, requireNotNull(selected)),
                "amount" to CivsProduction.amount(building))) else null,
            available?.exceptionOrNull()?.let { body(text.failure(player, it)) },
            if (city.construction?.buildingId == buildingId) body(player, "reason.construction-busy") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
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
        }, reopen = { showProduction(player, buildingId) })
    }

    private fun showSupplyRequests(player: Player, warehouseId: UUID, page: Int = 0) {
        val city = city(player) ?: return
        val warehouse = city.building(warehouseId) ?: return showBuildings(player)
        if (warehouse.type != BuildingType.WAREHOUSE) return showBuilding(player, warehouseId)
        val result = runCatching { host.supplyRequests(player) }
        val requests = result.getOrDefault(emptyList())
        val available = if (requests.isNotEmpty()) runCatching { host.availableMaterials(player) }
            else Result.success(emptyMap<String, Long>())
        val current = page.coerceIn(0, lastPage(requests.size, LOGISTICS_PAGE_SIZE))
        val visible = requests.drop(current * LOGISTICS_PAGE_SIZE).take(LOGISTICS_PAGE_SIZE)
        present(player, "civs.logistics", "logistics.title", buildList {
            result.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
            available.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
            if (result.isSuccess && requests.isEmpty()) add(body(player, "logistics.empty"))
            for ((buildingId, group) in visible.groupBy(CivSupplyRequest::buildingId)) {
                add(body(city.building(buildingId)?.let { buildingName(player, it.type, it.level) }
                    ?: tr(player, "buildings.generic")))
                add(DialogTables.body(group.map { request ->
                    val stock = if (available.isSuccess) text.literal(available.getOrNull()?.get(request.material) ?: 0)
                        else tr(player, "workers.stock-unknown")
                    var value = tr(player, "logistics.amounts", "remaining" to (request.needed - request.inTransit).coerceAtLeast(0),
                        "stock" to stock).color(WHITE)
                    if (request.inTransit > 0) value = value.append(Component.newline())
                        .append(tr(player, "logistics.in-transit", "amount" to request.inTransit).color(CivsText.BODY))
                    materialName(player, request.material).color(CivsText.BODY) to value
                }, tr(player, "logistics.item").color(STOCK) to tr(player, "logistics.remaining-stock").color(STOCK),
                    DialogTables.Frame.EPIC, 320, DialogTables.Columns.BALANCED))
            }
        }, buildList {
            pages(player, current, requests.size, LOGISTICS_PAGE_SIZE) { showSupplyRequests(player, warehouseId, it) }
        }, reopen = { showSupplyRequests(player, warehouseId, current) })
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
        val queued = queuePosition(city, building.id)
        val buildingInProgress = city.construction?.buildingId == building.id
        val eligible = !hallRequired && !buildingInProgress && queued == null && building.phase == BuildingPhase.ACTIVE
        present(player, "civs.upgrade", "upgrades.detail-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(buildList {
                add(tr(player, "label.level") to tr(player, "common.change", "before" to building.level, "after" to target))
                add(tr(player, "label.benefit") to upgradeBenefit(player, building))
                val slotsBefore = CivsRules.slots(city, building)
                val slotsAfter = CivsRules.slots(city, building.copy(level = target))
                if (slotsAfter > 0) add(tr(player, "label.workers") to tr(player, "common.change", "before" to slotsBefore, "after" to slotsAfter))
                if (city.inventory?.mode == CivStorageMode.VIRTUAL) add(tr(player, "warehouse.capacity") to tr(player, "common.change",
                    "before" to CivInventory.buildingCapacity(building), "after" to CivInventory.buildingCapacity(building.copy(level = target))))
                if (building.type == BuildingType.TOWN_HALL) add(tr(player, "label.radius") to tr(player, "common.change", "before" to city.radius, "after" to city.radius.toLong() + 32))
            }), body(player, "upgrades.materials-help"),
            when {
                queued != null -> body(tr(player, "queue.materials-help", "position" to queued))
                buildingInProgress -> body(player, "reason.construction-busy")
                hallRequired -> body(tr(player, "upgrades.hall-required", "level" to target))
                else -> null
            }, error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "prepare", "upgrades.prepare", COMMIT) {
                perform(player, "civs.preview", "preview.title", { host.prepareUpgrade(player, buildingId) },
                    success = { resumePlacement(player) }, failure = { showUpgrade(player, buildingId, it) },
                    cancelPending = { host.cancelPreview(player) })
            })
        }, reopen = { showUpgrade(player, buildingId) })
    }

    private fun showWorkers(player: Player, page: Int = 0, error: Component? = null) {
        val city = city(player) ?: return
        val current = page.coerceIn(0, lastPage(city.workers.size))
        val limit = minOf(CivsRules.populationLimit(city), CivsRules.beds(city))
        present(player, "civs.workers", "workers.title", listOfNotNull(
            body(player, "workers.help"), table(listOf(
                tr(player, "label.population") to tint(tr(player, "common.fraction", "value" to city.workers.size, "limit" to limit), PEOPLE),
                tr(player, "label.unassigned") to tint(text.literal(city.workers.count { it.assignment == null && !CivsResidents.isChild(it) }), STOCK),
            )), body(player, "guide.population-help"),
            busyBody(player), error?.let(::body),
        ), buildList {
            city.workers.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, worker ->
                add(valueButton(player, "worker_$index", "common.destination", text.literal(worker.name), PEOPLE,
                    workerStatus(player, worker)) { showWorker(player, worker.id) })
            }
            pages(player, current, city.workers.size) { showWorkers(player, it) }
        }, reopen = { showWorkers(player, current) })
    }

    private fun showWorker(player: Player, workerId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
        val building = worker.assignment?.let(city::building)
        val request = host.workerRequest(player, workerId)
        val toolKind = requestTool(request)
        present(player, "civs.worker", "workers.detail-title", listOfNotNull(
            body(tint(text.literal(worker.name), PEOPLE)), table(listOf(
                tr(player, "workers.health-label") to workerHealth(player, worker),
                tr(player, "workers.hunger-label") to tint(tr(player, "common.fraction", "value" to CivsResidents.hunger(worker), "limit" to 20),
                    if (CivsResidents.hunger(worker) <= 6) DANGER else STOCK),
                tr(player, "label.workplace") to (building?.let { buildingName(player, it.type, it.level) } ?: tr(player, "workers.unassigned")),
                tr(player, "label.profession") to tr(player, "profession.${if (CivsResidents.isChild(worker)) "child"
                    else building?.let { CivsRules.profession(city, it) } ?: "unassigned"}").let { tint(it, PEOPLE) },
                tr(player, "label.state") to workerStatus(player, worker),
                tr(player, "label.deliveries") to text.literal(worker.completedCycles),
            )), request?.let { body(tint(if (toolKind != null) tr(player, "workers.tool-request-$toolKind")
                else tr(player, "buildings.request", "item" to materialName(player, it.material.name), "amount" to it.amount), STOCK)) },
            worker.cargo?.let { cargo -> body(tr(player, "logistics.cargo", "item" to materialName(player, cargo.material),
                "amount" to cargo.amount, "target" to (CivsLogistics.destination(city, cargo)?.let {
                    buildingName(player, it.type, it.level) } ?: tr(player, "buildings.generic")))) },
            if (worker.cargo != null) body(player, "logistics.cargo-help") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            if (request != null) add(requestButton(player, request, "workers.supply-unavailable", "workers.supply-help", give = {
                perform(player, "civs.worker", "workers.detail-title", { host.giveWorkerRequest(player, workerId, request.material.name) },
                    success = { showWorker(player, workerId) }, failure = { showWorker(player, workerId, it) })
            }, refresh = { showWorker(player, workerId) }))
            add(button(player, "inventory", "workers.inventory-open", STOCK) { showWorkerInventory(player, workerId) })
            add(button(player, "skills", "workers.skills-open", PERSONAL) { showWorkerSkills(player, workerId) })
            if (!host.cityBusy(player) && CivsEquipment.plainCount(worker, "BREAD") > 0 &&
                CivsResidents.health(worker) < CivsResidents.maxHealth(worker)) {
                add(button(player, "feed", "workers.feed", COMMIT) {
                    perform(player, "civs.worker", "workers.detail-title", { host.feedWorker(player, workerId) },
                        success = { showWorker(player, workerId) }, failure = { showWorker(player, workerId, it) })
                }.copy(tooltip = tr(player, "workers.feed-help")))
            }
            if (!CivsResidents.isChild(worker) && !host.cityBusy(player)) add(button(player, "assign", "workers.assign", PERSONAL) { showAssignments(player, workerId) })
            if (worker.assignment != null && !host.cityBusy(player)) add(button(player, "unassign", "workers.unassign") {
                perform(player, "civs.worker", "workers.detail-title", { host.assign(player, workerId, null) },
                    success = { showWorker(player, workerId) }, failure = { showWorker(player, workerId, it) })
            })
        }, reopen = { showWorker(player, workerId) })
    }

    private fun showWorkerSkills(player: Player, workerId: UUID, page: Int = 0) {
        val city = city(player) ?: return
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
        val professions = CivsResidents.professions.map(BuildingType::valueOf)
        val current = page.coerceIn(0, lastPage(professions.size, SKILLS_PAGE_SIZE))
        present(player, "civs.worker-skills", "workers.skills-title", listOf(
            body(text.literal(worker.name)), body(player, "workers.skills-help"),
            table(professions.drop(current * SKILLS_PAGE_SIZE).take(SKILLS_PAGE_SIZE).map { type ->
                val level = CivsResidents.skillLevel(worker, type.name)
                tr(player, "profession.${CivsRules.profession(type)}") to if (level >= 5) tr(player, "workers.skill-max", "level" to level)
                else tr(player, "workers.skill-progress", "level" to level,
                    "xp" to CivsResidents.skillExperience(worker, type.name), "goal" to level * 10)
            }),
            body(tr(player, "workers.endurance-help", "health" to residentNumber(CivsResidents.maxHealth(worker)))),
        ), buildList { pages(player, current, professions.size, SKILLS_PAGE_SIZE) { showWorkerSkills(player, workerId, it) } },
            reopen = { showWorkerSkills(player, workerId, current) })
    }

    private fun showWorkerInventory(player: Player, workerId: UUID) {
        val city = city(player) ?: return
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        equipment.open(player, worker.name,
            items = { host.residentItems(player, workerId) },
            transfer = { actor, npcSlot, playerSlot, click -> host.transferResidentItem(actor, workerId, npcSlot, playerSlot, click) },
            back = {
                runtime.beginFlow(player)
                roots[player.uniqueId] = "civs.worker"
                showWorker(player, workerId)
            })
    }

    private fun workerHealth(player: Player, worker: CivWorker): Component = tint(tr(player, "common.fraction",
        "value" to residentNumber(CivsResidents.health(worker)), "limit" to residentNumber(CivsResidents.maxHealth(worker))),
        if (CivsResidents.health(worker) <= CivsResidents.maxHealth(worker) / 2) DANGER else COMMIT)

    private fun residentNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

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
        }, reopen = { showAssignments(player, workerId, current) })
    }

    private fun showMembers(player: Player, page: Int = 0, error: Component? = null) {
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
        if (member != city.owner && member !in city.members) return showMembers(player)
        present(player, "civs.member", "members.detail-title", listOfNotNull(
            body(if (member == city.owner) playerName(city, member) else tr(player, "members.detail", "name" to playerName(city, member))),
            if (member == city.owner) body(player, "members.owner") else null, error?.let(::body),
        ), if (city.owner == player.uniqueId && member != city.owner) listOf(button(player, "remove", "members.remove", DANGER) {
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
            pages(player, current, cities.size) { showAdmin(player, it) }
        }, reopen = { showAdmin(player, current) })
    }

    private fun showAdminCity(player: Player, cityId: UUID, error: Component? = null) {
        if (!isAdmin(player)) return showEntry(player)
        val city = host.settlements().firstOrNull { it.id == cityId } ?: return showAdmin(player)
        present(player, "civs.admin-city", "admin.city-title", listOfNotNull(
            body(text.literal(city.name)), table(listOf(
                tr(player, "label.owner") to playerName(city, city.owner),
                tr(player, "label.city-id") to text.literal(city.id),
                tr(player, "label.construction") to constructionText(player, city),
            )), city.construction?.failure?.let { body(text.reason(player, it)) }, error?.let(::body),
        ), buildList {
            if (city.construction?.phase == ConstructionPhase.PAUSED) add(button(player, "retry", "admin.retry", COMMIT) {
                perform(player, "civs.admin-city", "admin.city-title", { host.retryConstruction(player, cityId) },
                    success = { showAdminCity(player, cityId) }, failure = { showAdminCity(player, cityId, it) })
            })
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
            city.workers.size < CivsRules.populationLimit(city) && city.workers.size >= CivsRules.beds(city) -> "city.next-house"
            city.level() < 3 -> "city.next-upgrade"
            else -> "city.next-grow"
        }
        return tr(player, key)
    }

    private fun workerStatus(player: Player, worker: CivWorker): Component {
        val status = host.workerStatus(worker.id)
        return tint(text.status(player, status), statusColor(status))
    }

    private fun queuePosition(city: Settlement, buildingId: UUID): Int? =
        city.constructionQueue.orEmpty().indexOf(buildingId).takeIf { it >= 0 }?.plus(1)

    private fun buildingStatus(player: Player, city: Settlement, building: CivBuilding): Component {
        queuePosition(city, building.id)?.let { return tint(tr(player, "building-status.queued", "position" to it), STOCK) }
        val key = "building-status.${host.buildingStatus(building.id).removePrefix("building-status.")}"
        return tint(tr(player, key.takeIf { it in text.keys("ru") } ?: "workers.status.unavailable",
            "position" to queuePosition(city, building.id)), statusColor(key))
    }

    private fun upgradeBenefit(player: Player, building: CivBuilding): Component = when (building.type) {
        BuildingType.TOWN_HALL -> tr(player, "upgrades.population", "before" to building.level * 4, "after" to (building.level + 1) * 4)
        BuildingType.HOUSE -> tr(player, "upgrades.beds", "before" to building.level * 2, "after" to (building.level + 1) * 2)
        BuildingType.WAREHOUSE -> tr(player, "upgrades.warehouse")
        BuildingType.BUILDER_HUT -> tr(player, "upgrades.builder")
        BuildingType.FARM -> tr(player, "upgrades.field-worker")
        BuildingType.FORESTER -> tr(player, "upgrades.forest-worker")
        BuildingType.QUARRY -> tr(player, "upgrades.mining", "before" to building.level, "after" to building.level + 1)
        BuildingType.SAWMILL, BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY -> tr(player, "upgrades.processor")
        else -> tr(player, "upgrades.production", "before" to CivsProduction.amount(building),
            "after" to CivsProduction.amount(building.copy(level = building.level + 1)))
    }

    private fun constructionText(player: Player, city: Settlement): Component {
        val order = city.construction ?: return tr(player, "city.no-construction")
        val planned = order.plannedBuilding ?: city.building(order.buildingId)
        return tr(player, "city.construction", "building" to (planned?.let { text.building(player, it.type) } ?: tr(player, "buildings.generic")),
            "done" to order.workDone, "total" to order.workRequired,
            "state" to (planned?.let { buildingStatus(player, city, it) }
                ?: tr(player, "construction-phase.${order.phase.name.lowercase(Locale.ROOT)}")))
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

    private fun <T : Any> perform(player: Player, id: String, title: String, action: () -> CompletableFuture<T>,
        success: (T) -> Unit, failure: (Component) -> Unit, cancelPending: (() -> Unit)? = null,
        workingKey: String = "common.working") {
        val visit = present(player, id, title, listOf(body(player, workingKey)), emptyList()) ?: return
        visit.cancelPending = cancelPending
        val future = try { action() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { result, error ->
            if (!current(player, visit)) {
                CivsDiagnostics.event("ui_completion_ignored", "player" to player.uniqueId, "screen" to id, "visit" to visit.id)
                return@whenCompleteSync
            }
            visit.cancelPending = null
            if (error == null) success(requireNotNull(result)) else failure(text.failure(player, error))
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
        runtime.open(player, PaperDialogScreen(id = id, title = tint(tr(player, title), screenColor(id)), body = bodies,
            inputs = inputs, buttons = guarded,
            columns = if (guarded.size == 1) 1 else 2,
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
        PaperDialogButton(PaperDialogActionId.of(id), tint(tr(player, key), color), width = 230,
            closeDialogBeforeAction = close, onClick = action)

    private fun valueButton(player: Player, id: String, key: String, value: Component, color: TextColor = WHITE,
        tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id), tint(tr(player, key, "value" to value), color), tooltip,
            width = 230, onClick = { action() })

    private fun requestTool(request: CivsBuildingRequest?): String? =
        request?.material?.name?.substringAfterLast('_')?.lowercase(Locale.ROOT)?.takeIf { it in setOf("hoe", "axe", "pickaxe") }

    private fun requestButton(player: Player, request: CivsBuildingRequest, unavailableKey: String, helpKey: String,
        give: () -> Unit, refresh: () -> Unit): PaperDialogButton {
        val tool = requestTool(request)
        val busy = host.cityBusy(player)
        val canGive = request.canGive && !busy
        val color = if (canGive) COMMIT else DANGER
        val tooltip = tr(player, when {
            busy -> "reason.city-busy"
            !request.canGive -> if (tool == null) unavailableKey else "workers.tool-request-unavailable"
            tool != null -> "workers.give-tool-help"
            else -> helpKey
        })
        val action = { if (canGive) give() else refresh() }
        return if (tool != null) button(player, "give_request", "buildings.give-tool-$tool", color) { action() }.copy(tooltip = tooltip)
            else valueButton(player, "give_request", "buildings.give", materialName(player, request.material.name), color, tooltip, action)
    }

    private fun tint(value: Component, color: TextColor): Component =
        value.color(color).children(value.children().map { tint(it, color) })

    private fun MutableList<PaperDialogButton>.pages(player: Player, page: Int, count: Int, pageSize: Int = PAGE_SIZE, show: (Int) -> Unit) {
        if (page > 0) add(button(player, "previous", "common.previous", NAVIGATION) { show(page - 1) })
        if (page < lastPage(count, pageSize)) add(button(player, "next", "common.next", NAVIGATION) { show(page + 1) })
    }

    private fun lastPage(count: Int, pageSize: Int = PAGE_SIZE): Int = ((count - 1).coerceAtLeast(0)) / pageSize
    private fun isAdmin(player: Player): Boolean = player.hasPermission("arccivs.admin")
    private fun tr(player: Player, key: String, vararg values: Pair<String, Any?>): Component = text.render(player, key, *values)
    private fun body(player: Player, key: String): PaperDialogBody = body(tr(player, key))
    private fun body(component: Component): PaperDialogBody = PaperDialogBody(component, 320)
    private fun table(rows: List<Pair<Component, Component>>): PaperDialogBody = DialogTables.body(
        rows.map { (label, value) -> label.color(CivsText.BODY) to value.colorIfAbsent(WHITE) },
        null, DialogTables.Frame.EPIC, 320, DialogTables.Columns.VALUE_WIDE,
    )
    private fun busyBody(player: Player): PaperDialogBody? = if (host.cityBusy(player)) body(tint(tr(player, "reason.city-busy"), STOCK)) else null
    private fun materialName(player: Player, material: String): Component = text.material(player, material)
    private fun warehouseAmount(player: Player, own: Long, reserve: Long): Component = if (reserve > 0)
        tr(player, "warehouse.amount-with-reserve", "own" to own, "reserve" to reserve) else text.literal(own)
    private fun buildingName(player: Player, type: BuildingType, level: Int): Component =
        tint(tr(player, "buildings.name", "name" to text.building(player, type), "level" to level), buildingColor(type))
    private fun coordinates(player: Player, pos: CivPosition): Component = tr(player, "common.coordinates", "x" to pos.x, "y" to pos.y, "z" to pos.z)
    private fun playerName(city: Settlement, id: UUID): Component = tint(text.literal(host.memberName(city, id)), PEOPLE)

    private fun screenColor(id: String): TextColor = when (id) {
        "civs.workers", "civs.worker", "civs.members", "civs.member", "civs.member-add", "civs.building-workers" -> PEOPLE
        "civs.stock", "civs.warehouse", "civs.materials", "civs.building-materials", "civs.logistics",
        "civs.containers", "civs.container" -> STOCK
        "civs.management", "civs.upgrades", "civs.upgrade", "civs.worker-skills", "civs.production", "civs.storage-mode" -> PERSONAL
        "civs.guide", "civs.remote" -> NAVIGATION
        "civs.delete-city", "civs.delete-building" -> DANGER
        else -> LAND
    }

    private fun statusColor(status: String): TextColor = when (status.substringAfterLast('.')) {
        "placement-failed", "delivery-failed", "unreachable", "route-unreachable", "lands-denied",
        "world-changed", "protected-terrain", "building-overlap", "outside-city", "protected-area",
        "storage-unavailable", "injury-pending", "closed", "deleting" -> DANGER
        "active", "working", "working-field", "felling-tree", "digging-mine", "building", "placing",
        "delivering", "loading-cargo" -> COMMIT
        "to-work", "moving-to-work", "moving-to-storage", "to-store", "moving-to-pickup", "carrying-cargo" -> NAVIGATION
        "sleeping", "sheltering", "to-campfire", "campfire", "no-viewers" -> PERSONAL
        "playing", "wandering", "unassigned", "no-assignment", "no-construction" -> PEOPLE
        else -> STOCK
    }

    private fun buildingColor(type: BuildingType): TextColor = TextColor.color(when (type) {
        BuildingType.TOWN_HALL -> 0xE5BA73
        BuildingType.HOUSE -> 0xF3A2C9
        BuildingType.BUILDER_HUT -> 0xFFB277
        BuildingType.FARM -> 0xB7D878
        BuildingType.FORESTER -> 0x86C79A
        BuildingType.QUARRY -> 0xBDC4D4
        BuildingType.WAREHOUSE -> 0x86DCF1
        BuildingType.SAWMILL -> 0xD7B486
        BuildingType.BLACKSMITH -> 0xEF9D93
        BuildingType.STONEMASON -> 0xA8BDD8
        BuildingType.LIBRARY -> 0xC4ABFF
    })

    override fun close() {
        if (closed) return
        closed = true
        visits.clear()
        roots.clear()
        HandlerList.unregisterAll(this)
        equipment.close()
        runtime.close()
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val BUILDINGS_PAGE_SIZE = 16
        const val SKILLS_PAGE_SIZE = 6
        const val LOGISTICS_PAGE_SIZE = 3
        const val MATERIALS_PAGE_SIZE = 16
        val NAME = PaperDialogInputId.of("city_name")
        val DELETE_NAME = PaperDialogInputId.of("delete_city_name")
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
