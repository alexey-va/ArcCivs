package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogBody
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import ru.arc.civs.CivsUi.Companion.PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.LOGISTICS_MATERIAL_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.MATERIALS_PAGE_SIZE
import ru.arc.civs.CivsUi.Companion.WHITE
import ru.arc.civs.CivsUi.Companion.LAND
import ru.arc.civs.CivsUi.Companion.COMMIT
import ru.arc.civs.CivsUi.Companion.PERSONAL
import ru.arc.civs.CivsUi.Companion.PEOPLE
import ru.arc.civs.CivsUi.Companion.STOCK
import ru.arc.civs.CivsUi.Companion.NAVIGATION
import ru.arc.civs.CivsUi.Companion.DANGER

internal fun CivsUi.openInspectionInventory(player: Player, cityId: UUID, buildingId: UUID) {
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


internal fun CivsUi.showStock(player: Player, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val inventory = city.inventory
    if (inventory == null) {
        notice(player, "civs.stock", "stock.title", text.reason(player, "materials-migration-pending"), "common.settlement") { showOverview(player) }
        return
    }
    val buildings = city.buildings.filterNot { it.type.isInfrastructure }
    val current = page.coerceIn(0, lastPage(buildings.size))
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
        if (!physical || !busy) buildings.drop(current * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { index, building ->
            add(valueButton(player, "warehouse_$index", "common.destination", buildingName(player, building.type, building.level), STOCK) {
                openWarehouse(player, building.id)
            })
        }
        pages(player, current, buildings.size) { showStock(player, it) }
    }, reopen = { showStock(player, current) }, cityId = city.id)
}


internal fun CivsUi.showAvailableMaterials(player: Player, page: Int = 0) {
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
    }, reopen = { showAvailableMaterials(player, current) }, cityId = city.id)
}


internal fun CivsUi.showStorageMode(player: Player, error: Component? = null) {
    val city = city(player) ?: return
    val inventory = city.inventory ?: return showStock(player)
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
    }, reopen = { showStorageMode(player) }, cityId = city.id)
}


internal fun CivsUi.showContainers(player: Player, page: Int = 0, error: Component? = null) {
    val city = city(player) ?: return
    val inventory = city.inventory ?: return showStock(player)
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
    }, reopen = { showContainers(player, current) }, cityId = city.id)
}


internal fun CivsUi.showContainer(player: Player, position: CivPosition, error: Component? = null) {
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
    }, reopen = { showContainer(player, position) }, cityId = city.id)
}


internal fun CivsUi.openWarehouse(player: Player, buildingId: UUID) {
    val city = city(player) ?: return
    if (city.inventory?.mode != CivStorageMode.PHYSICAL) return showWarehouse(player, buildingId)
    if (host.cityBusy(player)) return showBuilding(player, buildingId, text.reason(player, "city-busy"))
    openIntake(player, buildingId)
}


internal fun CivsUi.showWarehouse(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
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
    }, reopen = { showWarehouse(player, buildingId, current) }, cityId = city.id)
}


internal fun CivsUi.showWithdraw(player: Player, buildingId: UUID, page: Int = 0, error: Component? = null) {
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
    }, reopen = { showWithdraw(player, buildingId, current) }, cityId = city.id)
}


internal fun CivsUi.openIntake(player: Player, buildingId: UUID) {
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


internal fun CivsUi.loadBuildingMaterials(player: Player, buildingId: UUID) {
    val city = city(player) ?: return
    city.construction?.takeIf { it.buildingId == buildingId }?.let {
        return showBuildingMaterials(player, buildingId, it.materials.orEmpty())
    }
    val visit = present(player, "civs.building-materials", "materials.title", listOf(body(player, "materials.loading")), emptyList(), cityId = city.id) ?: return
    val future = try { host.buildingMaterials(player, buildingId) } catch (error: Exception) { CompletableFuture.failedFuture(error) }
    future.whenCompleteSync(tasks, visit.epoch) { materials, error ->
        if (!current(player, visit)) return@whenCompleteSync
        if (error == null) showBuildingMaterials(player, buildingId, requireNotNull(materials))
        else showBuilding(player, buildingId, text.failure(player, error))
    }
}


internal fun CivsUi.showBuildingMaterials(player: Player, buildingId: UUID, estimate: Map<String, Long>, page: Int = 0, error: Component? = null) {
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
    }, reopen = { showBuildingMaterials(player, buildingId, estimate, current) }, cityId = city.id)
}


internal fun CivsUi.loadBuilderMaterials(player: Player, hutId: UUID, workerId: UUID? = null, page: Int = 0, feedback: Component? = null) {
    val city = city(player) ?: return
    val order = city.construction ?: return if (workerId == null) showBuilding(player, hutId, feedback)
        else showWorker(player, workerId, feedback)
    val visit = visits[player.uniqueId]?.takeIf { it.screenId == "civs.builder-materials" && current(player, it) }
        ?: present(player, "civs.builder-materials", "materials.title",
            listOf(body(player, "materials.loading")), emptyList(), cityId = city.id) ?: return
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


internal fun CivsUi.showBuilderMaterials(player: Player, hutId: UUID, workerId: UUID?, orderId: UUID,
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
    }, reopen = { loadBuilderMaterials(player, hutId, workerId, current) }, cityId = city.id)
}


internal fun CivsUi.showSupplyRequests(player: Player, warehouseId: UUID, page: Int = 0,
    materialPages: Map<UUID, Int> = emptyMap()) {
    val city = city(player) ?: return
    val warehouse = city.building(warehouseId) ?: return showBuildings(player)
    if (warehouse.type != BuildingType.WAREHOUSE) return showBuilding(player, warehouseId)
    val result = runCatching { host.supplyRequests(player) }
    val requests = result.getOrDefault(emptyList())
    data class SupplyCard(val id: UUID, val page: Int, val lastPage: Int, val body: PaperDialogBody, val lines: Int)
    val cards = requests.groupBy(CivSupplyRequest::buildingId).map { (buildingId, orders) ->
        val last = lastPage(orders.size, LOGISTICS_MATERIAL_PAGE_SIZE)
        val materialPage = (materialPages[buildingId] ?: 0).coerceIn(0, last)
        val building = city.building(buildingId)
        var source = building?.let { buildingName(player, it.type, it.level)
            .append(text.literal(" · ")).append(coordinates(player, it.control ?: it.entrance).color(CivsText.BODY)) }
            ?: tr(player, "buildings.generic")
        if (last > 0) source = source.append(text.literal(" · ")).append(tr(player, "logistics.material-page",
            "page" to materialPage + 1, "pages" to last + 1).color(CivsText.BODY))
        val table = presentation.supplyTable(orders.drop(materialPage * LOGISTICS_MATERIAL_PAGE_SIZE)
            .take(LOGISTICS_MATERIAL_PAGE_SIZE).map { request ->
            val remaining = (request.needed - request.inTransit).coerceAtLeast(0)
            listOf(materialName(player, request.material).color(STOCK),
                text.literal(remaining).color(CivsText.BODY))
        }, listOf(tr(player, "materials.item"), tr(player, "materials.missing")))
        val body = PaperDialogBody(source.append(Component.newline()).append(table.text), table.width)
        SupplyCard(buildingId, materialPage, last, body,
            PlainTextComponentSerializer.plainText().serialize(body.text).count { it == '\n' } + 1)
    }
    val sourcePages = mutableListOf<List<SupplyCard>>()
    for (card in cards) {
        val previous = sourcePages.lastOrNull()
        // Two short sources share a page only while their measured table rows fit a 360px viewport.
        if (card.lastPage == 0 && previous?.size == 1 && previous.single().lastPage == 0 &&
            previous.single().lines + card.lines <= 22) sourcePages[sourcePages.lastIndex] = previous + card
        else sourcePages += listOf(card)
    }
    val current = page.coerceIn(0, sourcePages.lastIndex.coerceAtLeast(0))
    val visible = sourcePages.getOrNull(current).orEmpty()
    present(player, "civs.logistics", "logistics.title", buildList {
        result.exceptionOrNull()?.let { add(body(text.failure(player, it))) }
        if (result.isSuccess && requests.isEmpty()) add(body(player, "logistics.empty"))
        addAll(visible.map(SupplyCard::body))
    }, buildList {
        visible.singleOrNull()?.takeIf { it.lastPage > 0 }?.let { card ->
            if (card.page > 0) add(button(player, "materials_previous", "logistics.materials-previous", NAVIGATION) {
                showSupplyRequests(player, warehouseId, current, materialPages + (card.id to card.page - 1))
            })
            if (card.page < card.lastPage) add(button(player, "materials_next", "logistics.materials-next", NAVIGATION) {
                showSupplyRequests(player, warehouseId, current, materialPages + (card.id to card.page + 1))
            })
        }
        if (current > 0) add(button(player, "previous", "logistics.sources-previous", NAVIGATION) {
            showSupplyRequests(player, warehouseId, current - 1, materialPages)
        })
        if (current < sourcePages.lastIndex) add(button(player, "next", "logistics.sources-next", NAVIGATION) {
            showSupplyRequests(player, warehouseId, current + 1, materialPages)
        })
    }, reopen = { showSupplyRequests(player, warehouseId, current, materialPages) }, cityId = city.id)
}
