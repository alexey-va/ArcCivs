package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.civs.CivsDialogPresentation.Action
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
    private val presentation = CivsDialogPresentation(plugin.config.getString("presentation.font", "").orEmpty().trim())

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

    /** Administrative village flow is independent of the player's personal settlement. */
    fun openAutonomous(player: Player, cityId: UUID? = null) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = if (cityId == null) "civs.autonomous" else "civs.autonomous-city"
        if (!isAdmin(player)) {
            autonomyDenied(player)
        } else if (cityId != null) {
            showAutonomousCity(player, cityId)
        } else if (host.preview(player)?.autonomous == true) {
            roots[player.uniqueId] = "civs.preview"
            showPreview(player)
        } else showAutonomous(player)
    }

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
        } else {
            val worker = city.workers.firstOrNull { it.id == workerId }
            val building = worker?.assignment?.let(city::building)
            if (building != null && CivsRules.isBuilder(city, building) && city.construction != null) {
                roots[player.uniqueId] = "civs.builder-materials"
                loadBuilderMaterials(player, building.id, workerId)
            } else showWorker(player, workerId)
        }
    }

    fun openCorpse(player: Player, cityId: UUID, corpseId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        val city = host.settlements().firstOrNull { it.id == cityId &&
            (if (it.autonomy != null) isAdmin(player) else it.canManage(player.uniqueId)) }
        val corpse = city?.corpses.orEmpty().firstOrNull { it.worker.id == corpseId }
        if (corpse == null) {
            roots[player.uniqueId] = "civs.corpse"
            notice(player, "civs.corpse", "corpse.items", text.reason(player, if (city == null) "not-member" else "worker-not-found")) { open(player) }
            return
        }
        val killerKey = "corpse.killer.${corpse.killerType?.lowercase(Locale.ROOT)}"
        val causeKey = "corpse.cause.${corpse.cause.lowercase(Locale.ROOT)}"
        val death = if (corpse.killerName != null || corpse.killerType != null) tr(player, "corpse.killed-by", "name" to
            (corpse.killerName?.let(text::literal) ?: tr(player, killerKey.takeIf { it in text.keys("ru") } ?: "corpse.killer.unknown")))
        else tr(player, "corpse.died-from", "cause" to tr(player, causeKey.takeIf { it in text.keys("ru") } ?: "corpse.cause.unknown"))
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        fun checkDeath() {
            if (host.settlements().firstOrNull { it.id == cityId }?.corpses.orEmpty().none {
                    it.worker.id == corpseId && it.diedAt == corpse.diedAt }) throw CivsRejection("worker-not-found")
        }
        equipment.open(player, corpse.worker.name, items = { checkDeath(); host.corpseItems(player, cityId, corpseId) },
            transfer = { actor, npcSlot, playerSlot, click ->
                checkDeath()
                if (npcSlot != null && playerSlot == null) host.transferCorpseItem(actor, cityId, corpseId, npcSlot, click)
                else CompletableFuture.failedFuture(CivsRejection("not-authorized"))
            }, back = { if (city?.autonomy != null) openAutonomous(player, cityId) else open(player) }, canDeposit = false,
            titleKey = if (corpse.worker.sex == CivSex.FEMALE) "corpse.inventory-title-female" else "corpse.inventory-title",
            backKey = "corpse.back",
            details = listOf(death))
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
        } else {
            val building = city.building(buildingId)
            if (building != null && CivsRules.isBuilder(city, building) && city.construction != null) {
                roots[player.uniqueId] = "civs.builder-materials"
                loadBuilderMaterials(player, buildingId)
            } else showBuilding(player, buildingId)
        }
    }

    /** Public Auto controls disclose the building and real barrel; management stays in its own flow. */
    fun openBuildingInspection(player: Player, cityId: UUID, buildingId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.building-inspection"
        showBuildingInspection(player, cityId, buildingId)
    }

    private fun showBuildingInspection(player: Player, cityId: UUID, buildingId: UUID, error: Component? = null) {
        val city = try { host.inspectBuilding(player, cityId, buildingId) } catch (failure: Exception) {
            present(player, "civs.building-inspection", "autonomy.building-title", listOf(body(text.failure(player, failure))),
                emptyList(), reopen = { showBuildingInspection(player, cityId, buildingId) })
            return
        }
        if (city.autonomy == null) return openBuilding(player, cityId, buildingId)
        val building = city.building(buildingId) ?: return
        val workers = city.workers.filter { it.assignment == buildingId }
        present(player, "civs.building-inspection", "autonomy.building-title", listOfNotNull(
            body(buildingName(player, building.type, building.level)),
            table(buildList {
                add(tr(player, "autonomy.village-label") to tint(text.literal(city.name), LAND))
                add(tr(player, "label.state") to buildingStatus(player, city, building))
                add(tr(player, "label.workers") to tint(tr(player, "common.fraction", "value" to workers.size,
                    "limit" to CivsRules.slots(city, building)), PEOPLE))
                CivsRules.resource(building.type)?.let { resource ->
                    add(tr(player, "label.output") to tr(player, "autonomy.production", "amount" to CivsRules.deliveryAmount(building),
                        "resource" to text.resource(player, resource)))
                }
            }),
            if (workers.isEmpty()) null else body(Component.join(Component.newline(), workers.map {
                tr(player, "common.detail", "label" to tint(text.literal(it.name), PEOPLE), "value" to workerStatus(player, it))
            })),
            body(tint(tr(player, "autonomy.shared-stock"), STOCK)),
            DialogTables.body(listOf(Resource.entries.map { tint(text.literal(city.stock[it]), STOCK) }),
                Resource.entries.map { text.resource(player, it) }, DialogTables.Frame.COMMON, 320, listOf(1, 1, 1)),
            body(player, if (isAdmin(player)) "autonomy.barrel-edit-help" else "autonomy.barrel-read-help"),
            error?.let(::body),
        ), listOf(
            button(player, "inventory", "autonomy.barrel-open", STOCK) { openInspectionInventory(player, cityId, buildingId) },
            button(player, "refresh", "autonomy.refresh", NAVIGATION) { showBuildingInspection(player, cityId, buildingId) },
        ), reopen = { showBuildingInspection(player, cityId, buildingId) })
    }

    private fun openInspectionInventory(player: Player, cityId: UUID, buildingId: UUID) {
        val epoch = tasks.token()
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        val future = try { host.openBuildingInventory(player, cityId, buildingId) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, epoch) { _, failure ->
            if (failure != null && !closed && player.isOnline && player.uniqueId !in visits) {
                runtime.beginFlow(player)
                roots[player.uniqueId] = "civs.building-inspection"
                showBuildingInspection(player, cityId, buildingId, text.failure(player, failure))
            }
        }
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
            if (host.foundingAllowed() && host.styles().isNotEmpty()) add(button(player, "found", "founding.begin", PERSONAL, appearance = Action.FOUND) { showStyles(player) })
            if (isAdmin(player)) add(button(player, "admin", "admin.open") { showAdmin(player) })
        }, reopen = { showEntry(player) }) }

    private fun showRemote(player: Player, feedback: Component? = null) {
        val remote = host.remoteCity(player) ?: return showEntry(player)
        val city = remote.city
        present(player, "civs.remote", "city.title", listOfNotNull(
            body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
            body(tr(player, "network.remote-help", "server" to remote.server.value)),
            facts(player, listOf(
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

    private fun showStyles(player: Player, page: Int = 0, autonomous: Boolean = false) {
        if (autonomous && !isAdmin(player)) return autonomyDenied(player)
        val styles = host.styles()
        val current = page.coerceIn(0, lastPage(styles.size))
        present(player, if (autonomous) "civs.autonomous-styles" else "civs.styles",
            if (autonomous) "autonomy.style-title" else "founding.style-title",
            listOf(body(player, if (autonomous) "autonomy.style-help" else "founding.style-help")),
            buildList {
                styles.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, style ->
                    add(valueButton(player, "style_$index", "common.choice", text.style(player, style)) { showName(player, style, autonomous = autonomous) })
                }
                pages(player, current, styles.size) { showStyles(player, it, autonomous) }
            }, reopen = { showStyles(player, current, autonomous) })
    }

    private fun showName(player: Player, style: String, initial: String = "", error: Component? = null, autonomous: Boolean = false) {
        if (autonomous && !isAdmin(player)) return autonomyDenied(player)
        var draft = initial
        present(player, if (autonomous) "civs.autonomous-name" else "civs.name",
            if (autonomous) "autonomy.name-title" else "founding.name-title",
            buildList {
                if (error != null) {
                    add(body(tr(player, "founding.prepare-failed").color(DANGER)))
                    add(body(error))
                } else add(body(player, if (autonomous) "autonomy.name-help" else "founding.name-help"))
                add(body(detail(player, "label.style", text.style(player, style))))
            },
            listOf(button(player, "prepare", if (error == null) "founding.prepare" else "founding.retry", COMMIT, appearance = Action.START) { context ->
                val name = context.text(NAME)?.trim().orEmpty()
                draft = name
                if (autonomous && !isAdmin(player)) autonomyDenied(player)
                else if (name.isBlank()) showName(player, style, name, text.reason(player, "invalid-name"), autonomous)
                else beginSelection(player, {
                    if (autonomous) host.beginAutonomousSelection(player, name, style) else host.beginFoundingSelection(player, name, style)
                }) {
                    showName(player, style, name, it, autonomous)
                }
            }),
            inputs = listOf(PaperDialogTextInput(NAME, tr(player, "founding.name-label"), initial = initial.take(32), width = 320, maxLength = 32)),
            reopen = { showName(player, style, draft, autonomous = autonomous) },
        )
    }

    private fun showPreview(player: Player, error: Component? = null, page: Int = 0) {
        val preview = host.preview(player)
        if (preview == null) {
            if (error == null) showEntry(player)
            else notice(player, "civs.preview", "preview.title", error) { showEntry(player) }
            return
        }
        if (preview.autonomous && !isAdmin(player)) return autonomyDenied(player)
        val materials = if (preview.autonomous) emptyList() else preview.materials.toSortedMap().entries.toList()
        val current = page.coerceIn(0, lastPage(materials.size))
        present(player, "civs.preview", "preview.title", listOfNotNull(
            body(player, if (preview.autonomous) "autonomy.preview-help" else if (preview.movable) "preview.site-help" else "preview.upgrade-help"),
            facts(player, listOf(
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
            if (preview.autonomous) body(player, "autonomy.resources-help") else null,
            body(player, "preview.protected"), error?.let(::body),
        ), buildList {
            add(button(player, "placement", "panel.return", NAVIGATION) { resumePlacement(player) })
            if (preview.autonomous) {
                add(button(player, "villages", "autonomy.open", PERSONAL) { showAutonomous(player) })
            } else if (host.city(player)?.canManage(player.uniqueId) == true) {
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
        val hall = city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
        present(player, "civs.overview", "city.title", listOfNotNull(
            body(tint(tr(player, "city.intro", "name" to city.name), LAND)),
            presentation.summary(Component.join(Component.newline(), listOf(
                tr(player, "city.summary-land", "level" to tint(text.literal(city.level()), PERSONAL),
                    "radius" to tint(tr(player, "city.radius", "radius" to city.radius), NAVIGATION)),
                tr(player, "city.summary-residents", "population" to tint(tr(player, "common.fraction", "value" to city.workers.size,
                    "limit" to CivsRules.populationLimit(city)), PEOPLE), "beds" to tint(text.literal(CivsRules.beds(city)), PEOPLE)),
                tr(player, "city.summary-activity", "buildings" to tint(tr(player, "common.fraction", "value" to city.buildings.size,
                    "limit" to CivsRules.MAX_BUILDINGS), LAND), "activity" to tint(tr(player, if (sleep) "city.sleeping" else "city.active"),
                    if (sleep) PERSONAL else COMMIT)),
                detail(player, "label.construction", constructionText(player, city)),
            ))),
            construction?.failure?.let { body(text.reason(player, it)) },
            presentation.divider(), presentation.paragraph(tint(nextStep(player, city), STOCK)), busyBody(player), feedback?.let(::body),
            hall?.takeIf { canUpgrade(city, it) }?.let { upgradeSummary(player, city, it) },
        ), buildList {
            add(button(player, "management", "management.open", PERSONAL) { showManagement(player) })
            add(button(player, "buildings", "buildings.open", LAND) { showBuildings(player) })
            add(button(player, "workers", "workers.open", PEOPLE) { showWorkers(player) })
            if (hall != null && canUpgrade(city, hall)) add(button(player, "upgrade", "upgrades.hall-start", COMMIT, appearance = Action.UPGRADE) {
                perform(player, "civs.overview", "city.title", { host.upgradeBuilding(player, hall.id) },
                    success = { showOverview(player) }, failure = { showOverview(player, it) }, workingKey = "upgrades.starting")
            })
            if (hall?.control != null && city.inventory != null && !host.cityBusy(player))
                add(button(player, "hall_inventory", "city.hall-inventory", STOCK) { openIntake(player, hall.id) }
                    .copy(tooltip = tr(player, "city.hall-inventory-help")))
            add(button(player, "guide", "guide.open", NAVIGATION) { showGuide(player) })
        }, reopen = { showOverview(player) })
    }

    private fun showGuide(player: Player) {
        city(player) ?: return
        present(player, "civs.guide", "guide.title", listOfNotNull(
            presentation.paragraph(tr(player, "city.boundary-help")),
            presentation.paragraph(tr(player, "city.sleep-rule")), presentation.divider(),
            presentation.callout(tr(player, "buildings.site-help")),
            presentation.paragraph(tr(player, "guide.population-help")),
            presentation.paragraph(tr(player, "guide.storage-help")),
        ), emptyList(), reopen = { showGuide(player) })
    }

    private fun showManagement(player: Player) {
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
        }, reopen = { showManagement(player) })
    }

    private fun showTestingSettings(player: Player, error: Component? = null) {
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
        }, reopen = { showTestingSettings(player) })
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
            body(player, if (physical) "stock.physical-help" else "stock.help"), facts(player, buildList {
                add(tr(player, "stock.mode-label") to tr(player, "stock.mode.${inventory.mode.name.lowercase(Locale.ROOT)}"))
                if (!physical) add(tr(player, "stock.reserve-label") to tr(player, "stock.items", "amount" to inventory.materials.values.sum()))
                else if (pending > 0) add(tr(player, "stock.pending-label") to tr(player, "stock.items", "amount" to pending))
            }), if (!physical) body(player, "stock.reserve-help") else null,
            if (physical && pending > 0) body(player, "stock.pending-help") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            add(button(player, "materials", "stock.available", STOCK) { showAvailableMaterials(player) })
            add(button(player, "mode", "stock.mode-open", PERSONAL, appearance = Action.SETTINGS) { showStorageMode(player) })
            add(button(player, "containers", "stock.containers-open", STOCK) { showContainers(player) })
            if (physical && pending > 0 && !busy) add(button(player, "materialize", "stock.materialize", COMMIT, appearance = Action.TAKE) {
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
            if (!busy && inventory.containers.size < CivInventory.MAX_CONTAINERS) add(button(player, "register", "stock.register", COMMIT, appearance = Action.ADD) {
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
            body(detail(player, "label.position", coordinates(player, position))),
            body(player, "stock.unregister-help"), busyBody(player), error?.let(::body),
        ), buildList {
            if (!host.cityBusy(player)) add(button(player, "unregister", "stock.unregister", DANGER, appearance = Action.DELETE) {
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
            body(detail(player, "warehouse.capacity", tr(player, "common.fraction",
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
                add(button(player, "deposit", "warehouse.deposit", COMMIT, appearance = Action.ADD) {
                    perform(player, "civs.warehouse", "warehouse.title", { host.depositMaterials(player, buildingId) },
                        success = { showWarehouse(player, buildingId, current) }, failure = { showWarehouse(player, buildingId, current, it) },
                        workingKey = null)
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
                        success = { showWarehouse(player, buildingId) }, failure = { showWithdraw(player, buildingId, current, it) },
                        workingKey = null)
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
        val current = page.coerceIn(0, lastPage(city.buildings.size, BUILDING_OVERVIEW_PAGE_SIZE))
        val buildings = city.buildings.drop(current * BUILDING_OVERVIEW_PAGE_SIZE).take(BUILDING_OVERVIEW_PAGE_SIZE)
        present(player, "civs.buildings", "buildings.title", listOfNotNull(
            body(player, "buildings.help"),
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
            if (city.buildings.size < CivsRules.MAX_BUILDINGS) add(button(player, "catalog", "buildings.catalog", PERSONAL) { showCatalog(player) })
            pages(player, current, city.buildings.size, BUILDING_OVERVIEW_PAGE_SIZE) { showBuildings(player, it) }
        }, reopen = { showBuildings(player, current) })
    }

    private fun showQueue(player: Player, page: Int = 0) {
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
            if (type in setOf(BuildingType.BUILDER_HUT, BuildingType.WAREHOUSE, BuildingType.SAWMILL) || CivsProduction.options(type).isNotEmpty())
                body(player, "buildings.workplaces-levels") else null,
            body(player, "buildings.site-help"),
            if (city.buildings.size >= CivsRules.MAX_BUILDINGS) body(player, "reason.building-limit") else null,
            error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "prepare", "buildings.prepare", COMMIT, appearance = Action.FOUND) {
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
            facts(player, buildList {
                add(tr(player, "label.level") to tint(text.literal(building.level), PERSONAL))
                add(tr(player, "label.control") to (building.control?.let { coordinates(player, it) } ?: tr(player, "control.unset")))
                add(tr(player, "label.state") to buildingStatus(player, city, building))
                add(tr(player, "label.workers") to tr(player, "common.fraction", "value" to assigned, "limit" to CivsRules.slots(city, building)))
                if (building.type == BuildingType.SAWMILL) add(tr(player, "label.output") to tr(player, "carpentry.automatic"))
                CivsProduction.material(building)?.takeIf { !CivsRules.isBuilder(city, building) && building.type != BuildingType.SAWMILL }?.let { material ->
                    if (building.type != BuildingType.QUARRY) add(tr(player, "label.output") to
                        if (building.type in setOf(BuildingType.FARM, BuildingType.FORESTER)) materialName(player, material)
                        else tr(player, "buildings.output", "amount" to CivsProduction.amount(building), "resource" to materialName(player, material)))
                }
                city.construction?.takeIf { it.buildingId == buildingId }?.let { order ->
                    add(tr(player, "label.construction") to tr(player, "common.fraction", "value" to order.workDone, "limit" to order.workRequired))
                }
            }), body(player, "buildings.control-help"),
            if (canUpgrade(city, building)) upgradeSummary(player, city, building) else null,
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
                        success = { showBuilding(player, buildingId) }, failure = { showBuilding(player, buildingId, it) },
                        workingKey = null)
                }, refresh = { showBuilding(player, buildingId) }))
            }
            if (city.inventory?.mode != CivStorageMode.PHYSICAL || !host.cityBusy(player)) {
                add(button(player, "warehouse", "warehouse.open", STOCK) { openWarehouse(player, buildingId) })
            }
            if (CivsRules.isBuilder(city, building) && city.construction != null) {
                add(button(player, "materials", "materials.open", COMMIT) { loadBuilderMaterials(player, buildingId) })
            } else if (building.phase == BuildingPhase.SITE || city.construction?.buildingId == buildingId || queued) {
                add(button(player, "materials", "materials.open", COMMIT) { loadBuildingMaterials(player, buildingId) })
            }
            if (queued || CivsRules.isBuilder(city, building) && city.constructionQueue.orEmpty().isNotEmpty())
                add(button(player, "queue", "queue.open", LAND) { showQueue(player) })
            if (CivsRules.slots(city, building) > 0) add(button(player, "workers", "buildings.workers", PEOPLE) { showBuildingWorkers(player, buildingId) })
            if (building.type == BuildingType.WAREHOUSE && city.inventory != null)
                add(button(player, "logistics", "logistics.open", STOCK) { showSupplyRequests(player, buildingId) })
            if ((building.type == BuildingType.SAWMILL || CivsProduction.options(building.type).isNotEmpty()) &&
                !CivsRules.isBuilder(city, building) && building.type != BuildingType.QUARRY)
                add(button(player, "production", if (building.type == BuildingType.SAWMILL) "carpentry.open" else "production.open", PERSONAL) {
                    showProduction(player, buildingId)
                })
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
            if (!host.cityBusy(player)) add(button(player, "delete", "delete-building.confirm", DANGER, appearance = Action.DELETE) {
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
        val available = runCatching { host.constructionAvailable(player, buildingId) }
        val incremental = order?.payment != null
        val required = if (order != null && !incremental) emptyMap() else CivsMaterials.unpaid(order?.materials ?: estimate)
        val entries = available.getOrNull()?.let { materialDeficit(required, it) }.orEmpty().toSortedMap().entries.toList()
        val current = page.coerceIn(0, lastPage(entries.size, MATERIALS_PAGE_SIZE))
        val canChange = !host.cityBusy(player) && available.isSuccess && (order == null || incremental)
        val canSupply = canChange && entries.isNotEmpty()
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
                materialName(player, material) to tint(text.literal(count), STOCK)
            }, tint(tr(player, "materials.item"), STOCK) to tint(tr(player, "materials.missing"), STOCK),
                DialogTables.Frame.LEGENDARY, 320, DialogTables.Columns.LABEL_WIDE)
            else if (available.isSuccess) body(player, if (required.isEmpty()) "materials.no-cost" else "materials.supplies-ready") else null,
            available.exceptionOrNull()?.let { body(text.failure(player, it)) },
        ), buildList {
            if (canSupply && player.gameMode != GameMode.CREATIVE) {
                add(button(player, "supply", "materials.supply", STOCK) {
                    perform(player, "civs.building-materials", "materials.title", { host.supplyConstruction(player, buildingId) },
                        success = ::supplied, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) },
                        workingKey = null)
                }.copy(tooltip = tr(player, "materials.supply-help")))
            }
            if (canSupply && (isAdmin(player) || player.gameMode == GameMode.CREATIVE))
                add(button(player, "grant", "materials.grant", COMMIT) {
                    perform(player, "civs.building-materials", "materials.title", { host.grantConstructionMaterials(player, buildingId) },
                        success = ::supplied, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) },
                        workingKey = null)
                }.copy(tooltip = tr(player, "materials.grant-help")))
            if (order == null && queued == null) add(button(player, "start",
                if (city.construction == null && city.constructionQueue.orEmpty().isEmpty()) "materials.start" else "queue.add", COMMIT) {
                perform(player, "civs.building-materials", "materials.title", { host.startBuilding(player, buildingId) },
                    success = { showBuilding(player, buildingId) }, failure = { showBuildingMaterials(player, buildingId, estimate, current, it) },
                    workingKey = "materials.preparing")
            }.copy(tooltip = tr(player, if (CivsRules.legacyHallBuilder(city)) "materials.legacy-start-help" else "materials.start-help")))
            pages(player, current, entries.size, MATERIALS_PAGE_SIZE) { showBuildingMaterials(player, buildingId, estimate, it) }
        }, reopen = { showBuildingMaterials(player, buildingId, estimate, current) })
    }

    private fun loadBuilderMaterials(player: Player, hutId: UUID, workerId: UUID? = null, page: Int = 0, feedback: Component? = null) {
        val city = city(player) ?: return
        val order = city.construction ?: return if (workerId == null) showBuilding(player, hutId, feedback)
            else showWorker(player, workerId, feedback)
        val visit = visits[player.uniqueId]?.takeIf { it.screenId == "civs.builder-materials" && current(player, it) }
            ?: present(player, "civs.builder-materials", "materials.title",
                listOf(body(player, "materials.loading")), emptyList()) ?: return
        val future = try { host.buildingMaterials(player, order.buildingId) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { materials, error ->
            if (!current(player, visit)) return@whenCompleteSync
            if (host.city(player)?.id != city.id) showEntry(player)
            else if (error != null) {
                val failure = text.failure(player, error)
                if (workerId == null) showBuilding(player, hutId, failure) else showWorker(player, workerId, failure)
            } else showBuilderMaterials(player, hutId, workerId, order.id, requireNotNull(materials), page, feedback)
        }
    }

    private fun showBuilderMaterials(player: Player, hutId: UUID, workerId: UUID?, orderId: UUID,
        materials: Map<String, Long>, page: Int, feedback: Component?) {
        val city = city(player) ?: return
        val hut = city.building(hutId) ?: return showBuildings(player)
        val worker = workerId?.let { id -> city.workers.firstOrNull { it.id == id && it.assignment == hutId } }
        if (!CivsRules.isBuilder(city, hut) || workerId != null && worker == null)
            return if (workerId == null) showBuilding(player, hutId) else showWorker(player, workerId)
        val order = city.construction
        if (order?.id != orderId) return loadBuilderMaterials(player, hutId, workerId, page, feedback)
        val target = city.building(order.buildingId) ?: return showBuilding(player, hutId)
        // A legacy hall may still employ a builder, but never becomes construction storage.
        val recipient = worker ?: if (hut.type != BuildingType.BUILDER_HUT)
            city.workers.firstOrNull { it.assignment == hutId && !CivsResidents.isChild(it) } else null
        val available = runCatching { host.constructionAvailable(player, target.id) }
        val required = if (order.payment == null) emptyMap() else CivsMaterials.unpaid(materials)
        val entries = available.getOrNull()?.let { materialDeficit(required, it) }.orEmpty().toSortedMap().entries.toList()
        val held = player.inventory.storageContents.filterNotNull().filter { item ->
            item.amount > 0 && item.isSimilar(ItemStack(item.type))
        }.groupBy { it.type.name }.mapValues { (_, items) -> items.sumOf { it.amount.toLong() } }
        val current = page.coerceIn(0, lastPage(entries.size, PAGE_SIZE))
        val needs = entries.isNotEmpty()
        val canChange = !host.cityBusy(player) && available.isSuccess && order.payment != null &&
            (recipient != null || hut.type == BuildingType.BUILDER_HUT)
        val canSupply = canChange && entries.any { (material, _) -> (held[material] ?: 0L) > 0 }
        val creative = player.gameMode == GameMode.CREATIVE
        val help = when {
            host.cityBusy(player) -> tr(player, "reason.city-busy")
            available.isFailure -> text.failure(player, requireNotNull(available.exceptionOrNull()))
            order.payment == null || !needs -> tr(player, "materials.supplies-ready")
            recipient == null && hut.type != BuildingType.BUILDER_HUT -> tr(player, "building-status.no-workers")
            !canSupply -> tr(player, "materials.inventory-empty")
            recipient != null -> tr(player, "materials.give-worker-help", "name" to recipient.name)
            else -> tr(player, "materials.give-hut-help")
        }
        fun reload(message: Component? = null) = loadBuilderMaterials(player, hutId, workerId, current, message)
        fun transfer(grant: Boolean) {
            perform(player, "civs.builder-materials", "materials.title", {
                if (recipient != null) {
                    if (grant) host.grantBuilderMaterials(player, recipient.id) else host.supplyBuilderMaterials(player, recipient.id)
                } else {
                    if (grant) host.grantConstructionMaterials(player, target.id, hutId) else host.supplyConstruction(player, target.id, hutId)
                }
            }, success = { result ->
                var message = tr(player, "materials.supplied", "moved" to result.moved, "remaining" to result.remaining)
                if (result.storageFull) message = message.append(Component.space()).append(tr(player, "materials.storage-full"))
                reload(message.color(if (result.remaining > 0) STOCK else COMMIT))
            }, failure = { reload(it) }, workingKey = null)
        }
        present(player, "civs.builder-materials", "materials.title", listOfNotNull(
            body(worker?.let { tint(text.literal(it.name), PEOPLE) } ?: buildingName(player, hut.type, hut.level)),
            PaperDialogBody(tr(player, "materials.current-build", "building" to buildingName(player, target.type, order.targetLevel),
                "done" to order.workDone, "total" to order.workRequired), 400),
            body(tr(player, if (recipient == null) "materials.hut-destination" else "materials.worker-destination",
                "name" to recipient?.name)),
            if (entries.isNotEmpty()) DialogTables.body(entries.drop(current * PAGE_SIZE).take(PAGE_SIZE).map { (material, count) ->
                materialName(player, material) to tint(text.literal(count), STOCK)
            }, tint(tr(player, "materials.item"), STOCK) to tint(tr(player, "materials.missing"), STOCK),
                DialogTables.Frame.LEGENDARY, 320, DialogTables.Columns.LABEL_WIDE)
            else if (available.isSuccess) body(player, "materials.supplies-ready") else null,
            body(player, "materials.builder-stock-help"),
            if (city.constructionQueue.orEmpty().isNotEmpty()) body(tr(player, "queue.waiting", "count" to city.constructionQueue.orEmpty().size)) else null,
            feedback?.let(::body), available.exceptionOrNull()?.let { body(text.failure(player, it)) },
        ), buildList {
            if (needs || available.isFailure) add(button(player, if (creative) "grant" else "supply", if (creative) "materials.grant" else "materials.supply",
                if (if (creative) canChange else canSupply) COMMIT else DANGER) {
                if (if (creative) canChange else canSupply) transfer(creative) else reload()
            }.copy(tooltip = if (creative && canChange) tr(player, "materials.grant-help") else help))
            if (needs && !creative && isAdmin(player)) add(button(player, "grant", "materials.grant", if (canChange) COMMIT else DANGER) {
                if (canChange) transfer(true) else reload()
            }.copy(tooltip = if (canChange) tr(player, "materials.grant-help") else help))
            if (workerId == null) add(button(player, "details", "materials.building-details", PERSONAL) { showBuilding(player, hutId) })
            else add(button(player, "details", "materials.worker-details", PEOPLE) { showWorker(player, workerId) })
            if (recipient != null) add(button(player, "inventory", "workers.inventory-open", STOCK) { showWorkerInventory(player, recipient.id) })
            else if (!host.cityBusy(player)) add(button(player, "warehouse", "warehouse.open", STOCK) { openWarehouse(player, hutId) })
            if (city.constructionQueue.orEmpty().isNotEmpty()) add(button(player, "queue", "queue.open", LAND) { showQueue(player) })
            pages(player, current, entries.size, PAGE_SIZE) { loadBuilderMaterials(player, hutId, workerId, it) }
        }, reopen = { loadBuilderMaterials(player, hutId, workerId, current) })
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
        }, reopen = { showBuildingWorkers(player, buildingId, current) })
    }

    private fun showProduction(player: Player, buildingId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (CivsRules.isBuilder(city, building)) return showBuilding(player, buildingId)
        if (building.type == BuildingType.SAWMILL) return showCarpentry(player, buildingId)
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

    private fun showCarpentry(player: Player, buildingId: UUID, page: Int = 0) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        if (building.type != BuildingType.SAWMILL) return showBuilding(player, buildingId)
        val result = runCatching { host.carpentry(player, buildingId) }
        val view = result.getOrNull()
        val orders = view?.orders.orEmpty()
        val current = page.coerceIn(0, lastPage(orders.size, CARPENTRY_PAGE_SIZE))
        present(player, "civs.production", "carpentry.title", buildList {
            add(body(buildingName(player, building.type, building.level)))
            add(body(player, "carpentry.help"))
            result.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
            if (view == null) add(body(player, "carpentry.unavailable"))
            else if (view.paused) add(body(tr(player, "carpentry.paused").color(STOCK)))
            else if (orders.isEmpty()) add(body(player, if (view.planning) "carpentry.planning" else "carpentry.waiting"))
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
            add(button(player, "refresh", "carpentry.refresh") { showCarpentry(player, buildingId, current) })
            pages(player, current, orders.size, CARPENTRY_PAGE_SIZE) { showCarpentry(player, buildingId, it) }
        }, reopen = { showCarpentry(player, buildingId, current) })
    }

    private fun showCarpentryRecipes(player: Player, buildingId: UUID, tier: Int, page: Int = 0) {
        val city = city(player) ?: return
        val building = city.building(buildingId) ?: return showBuildings(player)
        val view = runCatching { host.carpentry(player, buildingId) }.getOrNull() ?: return showCarpentry(player, buildingId)
        val recipes = view.recipes.filter { it.level == tier }
        val current = page.coerceIn(0, lastPage(recipes.size, CARPENTRY_PAGE_SIZE))
        present(player, "civs.carpentry-recipes", "carpentry.recipes-title", buildList {
            add(body(buildingName(player, building.type, building.level)))
            add(body(tr(player, "carpentry.tier", "level" to tier).color(PERSONAL)))
            add(body(player, "carpentry.recipes-help"))
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
        }, reopen = { showCarpentryRecipes(player, buildingId, tier, current) })
    }

    private fun materialAmounts(player: Player, materials: Map<String, Long>): Component =
        Component.join(text.literal(", "), materials.map { (material, amount) ->
            materialName(player, material).append(text.literal(" × $amount"))
        })

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
            if (requests.isNotEmpty()) add(body(player, "logistics.board-help"))
            result.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
            available.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
            if (result.isSuccess && requests.isEmpty()) add(body(player, "logistics.empty"))
            for (request in visible) {
                val remaining = (request.needed - request.inTransit).coerceAtLeast(0)
                val stock = available.getOrNull()?.get(request.material) ?: 0
                val state = when {
                    remaining == 0L && request.inTransit > 0 -> "in-transit"
                    request.inTransit > 0 -> "part-in-transit"
                    available.isFailure -> "stock-unknown"
                    stock == 0L -> "waiting-stock"
                    else -> "waiting-courier"
                }
                add(card(listOf(
                    city.building(request.buildingId)?.let { buildingName(player, it.type, it.level) }
                        ?: tr(player, "buildings.generic"),
                    materialName(player, request.material).color(STOCK),
                    tr(player, "logistics.card-amounts", "remaining" to remaining, "transit" to request.inTransit),
                    tr(player, "logistics.card-stock", "stock" to if (available.isSuccess) text.literal(stock)
                        else tr(player, "workers.stock-unknown")),
                    tr(player, "logistics.state.$state").color(if (state == "waiting-stock") STOCK else NAVIGATION),
                ), 360))
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
            upgradeSummary(player, city, building), body(player, "upgrades.materials-help"),
            when {
                queued != null -> body(tr(player, "queue.materials-help", "position" to queued))
                buildingInProgress -> body(player, "reason.construction-busy")
                hallRequired -> body(tr(player, "upgrades.hall-required", "level" to target))
                else -> null
            }, error?.let(::body),
        ), buildList {
            if (eligible) add(button(player, "upgrade", "upgrades.start", COMMIT, appearance = Action.UPGRADE) {
                perform(player, "civs.upgrade", "upgrades.detail-title", { host.upgradeBuilding(player, buildingId) },
                    success = { showBuilding(player, buildingId) }, failure = { showUpgrade(player, buildingId, it) },
                    workingKey = "upgrades.starting")
            })
        }, reopen = { showUpgrade(player, buildingId) })
    }

    private fun showWorkers(player: Player, page: Int = 0, error: Component? = null) {
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
        }, reopen = { showWorkers(player, current) })
    }

    private fun showWorker(player: Player, workerId: UUID, error: Component? = null) {
        val city = city(player) ?: return
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return showWorkers(player)
        val building = worker.assignment?.let(city::building)
        val request = host.workerRequest(player, workerId)
        val toolKind = requestTool(request)
        present(player, "civs.worker", "workers.detail-title", listOfNotNull(
            body(tint(text.literal(worker.name), PEOPLE)),
            body(text.residentVitals(player, CivsResidents.health(worker), CivsResidents.maxHealth(worker), CivsResidents.hunger(worker))),
            presentation.summary(listOfNotNull(
                tr(player, "label.profession") to tr(player, "profession.${if (CivsResidents.isChild(worker)) "child"
                    else building?.let { CivsRules.profession(city, it) } ?: "unassigned"}").color(PEOPLE),
                building?.let { tr(player, "label.workplace") to buildingName(player, it.type, it.level) },
                tr(player, "label.state") to workerStatus(player, worker),
                if (worker.completedCycles > 0) tr(player, "label.deliveries") to text.literal(worker.completedCycles) else null,
            )), request?.let { body(tint(if (toolKind != null) tr(player, "workers.tool-request-$toolKind")
                else tr(player, "buildings.request", "item" to materialName(player, it.material.name), "amount" to it.amount), STOCK)) },
            worker.cargo?.let { cargo -> body(tr(player, "logistics.cargo", "item" to materialName(player, cargo.material),
                "amount" to cargo.amount, "target" to (CivsLogistics.destination(city, cargo)?.let {
                    buildingName(player, it.type, it.level) } ?: tr(player, "buildings.generic")))) },
            if (worker.cargo != null) body(player, "logistics.cargo-help") else null,
            busyBody(player), error?.let(::body),
        ), buildList {
            if (building != null && CivsRules.isBuilder(city, building) && city.construction != null)
                add(button(player, "materials", "materials.open", COMMIT) { loadBuilderMaterials(player, building.id, workerId) })
            if (building?.type == BuildingType.WAREHOUSE)
                add(button(player, "logistics", "logistics.open", STOCK) { showSupplyRequests(player, building.id) })
            if (request != null) add(requestButton(player, request, "workers.supply-unavailable", "workers.supply-help", give = {
                perform(player, "civs.worker", "workers.detail-title", { host.giveWorkerRequest(player, workerId, request.material.name) },
                    success = { showWorker(player, workerId) }, failure = { showWorker(player, workerId, it) },
                    workingKey = null)
            }, refresh = { showWorker(player, workerId) }))
            add(button(player, "inventory", "workers.inventory-open", STOCK) { showWorkerInventory(player, workerId) })
            add(button(player, "skills", "workers.skills-open", PERSONAL) { showWorkerSkills(player, workerId) })
            if (!host.cityBusy(player) && CivsEquipment.plainCount(worker, "BREAD") > 0 &&
                CivsResidents.health(worker) < CivsResidents.maxHealth(worker)) {
                add(button(player, "feed", "workers.feed", COMMIT, appearance = Action.CONFIRM) {
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
            }, frame = DialogTables.Frame.EPIC),
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
            listOf(button(player, "add", "members.add-confirm", COMMIT, appearance = Action.ADD) { context ->
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
        ), if (city.owner == player.uniqueId && member != city.owner) listOf(button(player, "remove", "members.remove", DANGER, appearance = Action.DELETE) {
            perform(player, "civs.member", "members.detail-title", { host.removeMember(player, member) },
                success = { showMembers(player) }, failure = { showMember(player, member, it) })
        }) else emptyList(), reopen = { showMember(player, member) })
    }

    private fun autonomyDenied(player: Player) {
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.autonomous-denied"
        present(player, "civs.autonomous-denied", "autonomy.title", listOf(body(text.reason(player, "not-authorized"))),
            emptyList(), reopen = { autonomyDenied(player) })
    }

    private fun autonomyStage(player: Player, status: CivsAutonomyView): Component {
        val key = "autonomy.stage.${status.stage}"
        return tr(player, key.takeIf { it in text.keys("ru") } ?: "autonomy.stage.unavailable")
            .color(when (status.stage) {
                "blocked", "unhealthy" -> DANGER
                "paused", "resources" -> STOCK
                "complete" -> COMMIT
                else -> NAVIGATION
            })
    }

    private fun autonomousCity(player: Player, cityId: UUID): Settlement? {
        if (!isAdmin(player)) {
            autonomyDenied(player)
            return null
        }
        val city = host.settlements().firstOrNull { it.id == cityId && it.autonomy != null }
        if (city == null) notice(player, "civs.autonomous-city", "autonomy.detail-title", tr(player, "autonomy.not-found")) {
            showAutonomous(player)
        }
        return city
    }

    private fun showAutonomous(player: Player, page: Int = 0) {
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

    private fun showAutonomousCity(player: Player, cityId: UUID, feedback: Component? = null) {
        val city = autonomousCity(player, cityId) ?: return
        val paused = city.autonomy!!.paused
        val status = if (paused) CivsAutonomyView("paused") else host.autonomyStatus(city.id) ?: CivsAutonomyView("unavailable")
        val target = status.target?.let { type -> status.level?.let { buildingName(player, type, it) } ?: text.building(player, type) }
        val missing = Resource.entries.filter { status.missing[it] > 0 }
        val completed = CivsAutonomy.profile.count { type -> city.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE && it.level >= 2 } }
        present(player, "civs.autonomous-city", "autonomy.detail-title", listOfNotNull(
            body(tint(text.literal(city.name), LAND)),
            presentation.summary(Component.join(Component.newline(), buildList {
                add(autonomyStage(player, status))
                target?.let { add(tr(player, "autonomy.goal", "target" to it)) }
                add(tr(player, "autonomy.progress", "count" to completed, "total" to CivsAutonomy.profile.size))
            })),
            status.reason?.let { body(text.reason(player, it)) },
            if (missing.isNotEmpty()) presentation.paragraph(tint(tr(player, "autonomy.missing", "resources" to
                Component.join(Component.text(" · "), missing.map { resource ->
                    tr(player, "common.detail", "label" to text.resource(player, resource), "value" to status.missing[resource])
                })), STOCK)) else null,
            facts(player, Resource.entries.map { text.resource(player, it) to tint(text.literal(city.stock[it]), STOCK) }),
            body(tr(player, "autonomy.population", "buildings" to city.buildings.size, "workers" to city.workers.size,
                "assigned" to city.workers.count { it.assignment != null })),
            body(player, if (paused) "autonomy.paused-help" else "autonomy.resources-help"), feedback?.let(::body),
        ), buildList {
            add(button(player, "buildings", "buildings.open", LAND) { showAutonomousBuildings(player, city.id) })
            add(button(player, "workers", "workers.open", PEOPLE) { showAutonomousWorkers(player, city.id) })
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
            })
            add(button(player, "refresh", "autonomy.refresh", NAVIGATION) { showAutonomousCity(player, city.id) })
            add(button(player, "villages", "autonomy.open", PERSONAL) { showAutonomous(player) })
        }, reopen = { showAutonomousCity(player, city.id) })
    }

    private fun showAutonomousBuildings(player: Player, cityId: UUID, page: Int = 0) {
        val city = autonomousCity(player, cityId) ?: return
        val current = page.coerceIn(0, lastPage(city.buildings.size, BUILDING_OVERVIEW_PAGE_SIZE))
        val buildings = city.buildings.drop(current * BUILDING_OVERVIEW_PAGE_SIZE).take(BUILDING_OVERVIEW_PAGE_SIZE)
        present(player, "civs.autonomous-buildings", "buildings.title", listOfNotNull(
            body(tint(text.literal(city.name), LAND)), body(player, "autonomy.buildings-help"),
            if (buildings.isEmpty()) body(player, "autonomy.buildings-empty") else DialogTables.body(buildings.map { building -> listOf(
                tint(text.building(player, building.type), buildingColor(building.type)), tint(text.literal(building.level), PERSONAL),
                buildingSummaryStatus(player, city, building), tint(text.literal(city.workers.count { it.assignment == building.id }), PEOPLE),
            ) }, listOf("building", "level", "state", "workers").map { tr(player, "buildings.column-$it") },
                DialogTables.Frame.RARE, 400, listOf(5, 1, 5, 3)),
        ), buildList {
            pages(player, current, city.buildings.size, BUILDING_OVERVIEW_PAGE_SIZE) { showAutonomousBuildings(player, city.id, it) }
        }, reopen = { showAutonomousBuildings(player, city.id, current) })
    }

    private fun showAutonomousWorkers(player: Player, cityId: UUID, page: Int = 0) {
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
            pages(player, current, city.workers.size) { showAutonomousWorkers(player, city.id, it) }
        }, reopen = { showAutonomousWorkers(player, city.id, current) })
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
        ), frame = DialogTables.Frame.RARE), error?.let(::body)), buildList {
            add(button(player, "autonomous", "autonomy.open", LAND, appearance = Action.FOUND) { showAutonomous(player) })
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
        }, reopen = { showAdminCity(player, cityId) })
    }

    private fun showReload(player: Player, error: Component? = null) {
        if (!isAdmin(player)) return showEntry(player)
        present(player, "civs.admin-reload", "admin.reload-title", listOfNotNull(body(player, "admin.reload-help"), error?.let(::body)),
            listOf(button(player, "confirm", "admin.reload-confirm", COMMIT, appearance = Action.RESET) {
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

    private fun buildingSummaryStatus(player: Player, city: Settlement, building: CivBuilding): Component {
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

    private fun canUpgrade(city: Settlement, building: CivBuilding): Boolean =
        building.level < 3 && building.phase == BuildingPhase.ACTIVE && queuePosition(city, building.id) == null &&
            city.construction?.buildingId != building.id && (building.type == BuildingType.TOWN_HALL || building.level < city.level())

    private fun upgradeSummary(player: Player, city: Settlement, building: CivBuilding): PaperDialogBody = presentation.paragraph(Component.join(Component.newline(), buildList {
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

    private fun materialDeficit(required: Map<String, Long>, available: Map<String, Long>): Map<String, Long> =
        required.mapValues { (material, count) -> (count - (available[material] ?: 0L)).coerceAtLeast(0L) }.filterValues { it > 0L }

    private fun upgradeBenefit(player: Player, building: CivBuilding): Component = when (building.type) {
        BuildingType.TOWN_HALL -> tr(player, "upgrades.population", "before" to building.level * 4, "after" to (building.level + 1) * 4)
        BuildingType.HOUSE -> tr(player, "upgrades.beds", "before" to building.level * 2, "after" to (building.level + 1) * 2)
        BuildingType.WAREHOUSE -> tr(player, "upgrades.warehouse")
        BuildingType.BUILDER_HUT -> tr(player, "upgrades.builder")
        BuildingType.FARM -> tr(player, "upgrades.field-worker")
        BuildingType.FORESTER -> tr(player, "upgrades.forest-worker")
        BuildingType.QUARRY -> tr(player, "upgrades.mining", "before" to building.level, "after" to building.level + 1)
        BuildingType.SAWMILL -> tr(player, "upgrades.carpentry", "level" to building.level + 1)
        BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY -> tr(player, "upgrades.processor")
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
        workingKey: String? = "common.working") {
        // Transfers retain the consumed visit and its table until fresh state can replace it once.
        val visit = if (workingKey == null) visits[player.uniqueId]?.takeIf { it.screenId == id && current(player, it) }
            else present(player, id, title, listOf(body(player, workingKey)), emptyList())
        if (visit == null) return
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
            exitButton = presentation.button(button(player, "history", footerKey).copy(width = 200),
                if (footerKey == "common.close") Action.CLOSE else Action.BACK),
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
        close: Boolean = false, appearance: Action? = null,
        action: ru.arc.paper.menu.PaperDialogClickHandler = ru.arc.paper.menu.PaperDialogClickHandler {}): PaperDialogButton =
        presentation.button(PaperDialogButton(PaperDialogActionId.of(id), tint(tr(player, key), color), width = 230,
            closeDialogBeforeAction = close, onClick = action), appearance)

    private fun valueButton(player: Player, id: String, key: String, value: Component, color: TextColor = WHITE,
        tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id), tint(tr(player, key, "value" to value), color), tooltip,
            width = 230, onClick = { action() })

    private fun requestTool(request: CivsBuildingRequest?): String? =
        request?.material?.name?.substringAfterLast('_')?.lowercase(Locale.ROOT)?.takeIf { it in setOf("hoe", "axe", "pickaxe") }

    private fun requestButton(player: Player, request: CivsBuildingRequest, unavailableKey: String, helpKey: String,
        give: () -> Unit, refresh: () -> Unit): PaperDialogButton {
        val tool = requestTool(request)
        val creative = player.gameMode == GameMode.CREATIVE
        val creativeTool = tool != null && creative
        val busy = host.cityBusy(player)
        val canGive = request.canGive && !busy
        val color = if (canGive) COMMIT else DANGER
        val tooltip = tr(player, when {
            busy -> "reason.city-busy"
            !request.canGive -> when {
                creativeTool -> "workers.tool-request-creative-unavailable"
                tool != null -> "workers.tool-request-unavailable"
                creative -> "$unavailableKey-creative"
                else -> unavailableKey
            }
            creativeTool -> "workers.give-tool-creative-help"
            tool != null -> "workers.give-tool-help"
            creative -> "$helpKey-creative"
            else -> helpKey
        })
        val action = { if (canGive) give() else refresh() }
        return if (tool != null) button(player, "give_request", "buildings.give-tool-$tool", color) { action() }.copy(tooltip = tooltip)
            else valueButton(player, "give_request", "buildings.give", materialName(player, request.material.name), color, tooltip, action)
    }

    private fun tint(value: Component, color: TextColor): Component =
        value.color(color).children(value.children().map { tint(it, color) })

    private fun MutableList<PaperDialogButton>.pages(player: Player, page: Int, count: Int, pageSize: Int = PAGE_SIZE, show: (Int) -> Unit) {
        if (page > 0) add(button(player, "previous", "common.previous", NAVIGATION, appearance = Action.PREVIOUS) { show(page - 1) })
        if (page < lastPage(count, pageSize)) add(button(player, "next", "common.next", NAVIGATION, appearance = Action.NEXT) { show(page + 1) })
    }

    private fun lastPage(count: Int, pageSize: Int = PAGE_SIZE): Int = ((count - 1).coerceAtLeast(0)) / pageSize
    private fun isAdmin(player: Player): Boolean = player.hasPermission("arccivs.admin")
    private fun tr(player: Player, key: String, vararg values: Pair<String, Any?>): Component = text.render(player, key, *values)
    private fun body(player: Player, key: String): PaperDialogBody = presentation.paragraph(tr(player, key))
    private fun body(component: Component): PaperDialogBody = PaperDialogBody(component, 320)
    private fun detail(player: Player, label: String, value: Component): Component =
        tr(player, "common.detail", "label" to tr(player, label), "value" to value)
    private fun card(lines: List<Component>, width: Int = 320): PaperDialogBody =
        DialogTables.framedBody(Component.join(Component.newline(), lines), DialogTables.Frame.COMMON, width)
    private fun facts(player: Player, rows: List<Pair<Component, Component>>): PaperDialogBody =
        presentation.paragraph(Component.join(Component.newline(), rows.map { (label, value) ->
            tr(player, "common.detail", "label" to label, "value" to value)
        }))
    private fun table(rows: List<Pair<Component, Component>>, columns: DialogTables.Columns = DialogTables.Columns.VALUE_WIDE,
        frame: DialogTables.Frame = DialogTables.Frame.UNCOMMON): PaperDialogBody = DialogTables.body(
        rows.map { (label, value) -> label.color(CivsText.BODY) to value.colorIfAbsent(WHITE) },
        null, frame, 320, columns,
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
        "civs.stock", "civs.warehouse", "civs.materials", "civs.building-materials", "civs.builder-materials", "civs.logistics",
        "civs.containers", "civs.container" -> STOCK
        "civs.management", "civs.upgrades", "civs.upgrade", "civs.worker-skills", "civs.production", "civs.carpentry-recipes", "civs.storage-mode", "civs.testing-settings" -> PERSONAL
        "civs.guide", "civs.remote" -> NAVIGATION
        "civs.delete-city", "civs.delete-building" -> DANGER
        else -> LAND
    }

    private fun statusColor(status: String): TextColor = when (status.substringAfterLast('.')) {
        "placement-failed", "delivery-failed", "unreachable", "route-unreachable", "lands-denied",
        "world-changed", "protected-terrain", "building-overlap", "outside-city", "protected-area",
        "storage-unavailable", "injury-pending", "closed", "deleting" -> DANGER
        "active", "working", "working-field", "watering", "fertilizing", "felling-tree", "digging-mine", "building", "placing",
        "delivering", "loading-cargo", "collecting-supplies" -> COMMIT
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
        const val BUILDING_OVERVIEW_PAGE_SIZE = 8
        const val SKILLS_PAGE_SIZE = 6
        const val LOGISTICS_PAGE_SIZE = 3
        const val CARPENTRY_PAGE_SIZE = 3
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
