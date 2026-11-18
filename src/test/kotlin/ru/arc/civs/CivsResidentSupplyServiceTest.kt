package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen

class CivsResidentSupplyServiceTest : FunSpec({
    for (viaBuilding in listOf(false, true)) test("creative empty inventory issues the requested pickaxe durably viaBuilding=$viaBuilding") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("CreativeToolOwner").also {
                it.isOp = true; it.gameMode = org.bukkit.GameMode.CREATIVE
                it.teleport(Location(h.world, 0.0, 65.0, 0.0)); it.inventory.clear()
            }
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY)
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it == 4) CivResidentItem("IRON_AXE", 1) else null })
            val ready = h.city.copy(owner = player.uniqueId, buildings = listOf(h.city.buildings.first(), quarry),
                memberNames = mapOf(player.uniqueId.toString() to player.name),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.service.workerRequest(player, worker.id) shouldBe CivsBuildingRequest(Material.IRON_PICKAXE, 1, true)
            h.service.buildingRequest(player, quarry.id) shouldBe CivsBuildingRequest(Material.IRON_PICKAXE, 1, true)
            val issue = if (viaBuilding) h.service.giveBuildingRequest(player, quarry.id, "IRON_PICKAXE")
                else h.service.giveWorkerRequest(player, worker.id, "IRON_PICKAXE")
            val tx = requireNotNull(receipt)
            tx.residentTransfer shouldBe null
            tx.changes shouldBe emptyList()
            CivsEquipment.slots(tx.after.workers.single())[4] shouldBe CivResidentItem("IRON_PICKAXE", 1)
            CivsEquipment.plainCount(tx.after.workers.single(), "IRON_AXE") shouldBe 1
            CivsEquipment.slots(h.service.city(player)!!.workers.single())[4] shouldBe CivResidentItem("IRON_AXE", 1)
            player.inventory.storageContents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
            issue.isDone shouldBe false
            journal.complete(tx); h.tick(); h.commit(); issue.join()
            CivsEquipment.slots(h.service.city(player)!!.workers.single())[4] shouldBe CivResidentItem("IRON_PICKAXE", 1)
            h.service.giveRequestedTool(player, worker.id).isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            player.inventory.storageContents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
        }
    }

    test("survival operator cannot issue a requested pickaxe without a real player item") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SurvivalToolOwner").also { it.isOp = true; it.inventory.clear() }
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY)
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(owner = player.uniqueId,
                buildings = listOf(h.city.buildings.first(), quarry), workers = listOf(worker), stock = Stock(),
                inventory = CivInventory(CivStorageMode.VIRTUAL))))
            h.start()
            h.service.toolRequest(player, worker.id) shouldBe CivsToolRequest(Material.IRON_PICKAXE, false)
            h.service.giveRequestedTool(player, worker.id).isCompletedExceptionally shouldBe true
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), "IRON_PICKAXE") shouldBe 0
        }
    }

    for (viaBuilding in listOf(false, true)) for (material in listOf("TORCH", "LADDER"))
        test("creative request dialog supplies $material through its durable destination viaBuilding=$viaBuilding") {
            serviceScenario { h ->
                val world = CivsWorksiteWorldFixture("service-creative-supply").also(h.paper.server::addWorld)
                world.loadChunk(0, 0)
                val player = object : org.mockbukkit.mockbukkit.entity.PlayerMock(h.paper.server, "CreativeSupplyOwner") {
                    override fun closeDialog() = Unit
                }.also(h.paper.server::addPlayer).also {
                    it.addAttachment(h.plugin, "arccivs.use", true)
                    it.gameMode = org.bukkit.GameMode.CREATIVE
                    it.setLocale(java.util.Locale.US)
                    it.teleport(Location(world, 10.0, 65.0, 0.0))
                    it.inventory.clear()
                }
                val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY,
                    schematicId = "civs/minecolonies/nordic/fundamentals/miner1", control = CivPosition(10, 64, 0))
                world.getBlockAt(10, 64, 0).type = Material.BARREL
                val barrel = (world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
                val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1,
                    items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                        4 -> CivResidentItem("DIAMOND_PICKAXE", 1)
                        6, 7, 8 -> CivsLogistics.QUARRY_SUPPLIES.entries.elementAt(slot - 6).let { (item, amount) ->
                            if (item == material) null else CivResidentItem(item, amount.toInt())
                        }
                        else -> null
                    } })
                val ready = h.city.copy(owner = player.uniqueId, worldId = world.uid,
                    memberNames = mapOf(player.uniqueId.toString() to player.name),
                    buildings = listOf(h.city.buildings.first(), quarry), workers = listOf(worker), stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
                var journal = CompletableFuture<CivInventoryTransaction>()
                var receipt: CivInventoryTransaction? = null
                every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
                every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
                h.start()
                val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                    .get(h.service) as CivsWorksites
                worksites.pulse(listOf(requireNotNull(h.service.city(player))))
                val amount = CivsLogistics.QUARRY_SUPPLIES.getValue(material)
                h.service.workerRequest(player, worker.id) shouldBe CivsBuildingRequest(Material.valueOf(material), amount, true)
                h.service.buildingRequest(player, quarry.id) shouldBe CivsBuildingRequest(Material.valueOf(material), amount, true)
                RequestDialog(h, player).use { dialog ->
                    if (viaBuilding) dialog.ui.openBuilding(player, ready.id, quarry.id)
                    else dialog.ui.openWorker(player, ready.id, worker.id)
                    val give = dialog.screen.buttons.single { it.id.value == "give_request" }
                    give.label.color() shouldBe net.kyori.adventure.text.format.TextColor.color(0x9BD48D)
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(give.tooltip!!)
                        .contains(if (viaBuilding) "barrel" else "bag") shouldBe true
                    var action = dialog.action("give_request")
                    dialog.click(action)
                    dialog.click(action)
                    val rejected = requireNotNull(receipt)
                    // This known pre-write rejection must release admission without creating either endpoint.
                    journal.completeExceptionally(CivsRejection("storage-transaction-too-large")); h.tick()
                    h.saves shouldBe emptyList()
                    CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), material) shouldBe 0
                    barrel.contents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
                    journal = CompletableFuture()
                    if (viaBuilding) dialog.ui.openBuilding(player, ready.id, quarry.id)
                    else dialog.ui.openWorker(player, ready.id, worker.id)
                    action = dialog.action("give_request")
                    dialog.click(action)
                    dialog.click(action)
                    val tx = requireNotNull(receipt)
                    (tx.id == rejected.id) shouldBe false
                    tx.playerDebit shouldBe null
                    tx.residentTransfer shouldBe null
                    player.inventory.storageContents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
                    CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), material) shouldBe 0
                    barrel.contents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
                    h.saves shouldBe emptyList()
                    journal.complete(tx); h.tick()
                    CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), material) shouldBe 0
                    if (viaBuilding) barrel.contents.filterNotNull().sumOf { if (it.type.name == material) it.amount else 0 } shouldBe amount.toInt()
                    h.commit()
                    val supplied = h.service.city(player)!!.workers.single()
                    CivsEquipment.plainCount(supplied, material) shouldBe if (viaBuilding) 0 else amount.toInt()
                    if (!viaBuilding) barrel.contents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
                    dialog.click(action)
                    val repeat = if (viaBuilding) h.service.giveBuildingRequest(player, quarry.id, material)
                        else h.service.giveWorkerRequest(player, worker.id, material)
                    repeat.isCompletedExceptionally shouldBe true
                    verify(exactly = 2) { h.store.saveInventoryTransaction(any()) }
                    player.inventory.storageContents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
                }
            }
        }

    for (viaBuilding in listOf(false, true)) for (guard in listOf("full", "survival", "stale"))
        test("requested supply guard $guard leaves both endpoints and revision intact viaBuilding=$viaBuilding") {
            serviceScenario { h ->
                val player = h.paper.addPlayer("SupplyGuardOwner").also {
                    it.addAttachment(h.plugin, "arccivs.use", true)
                    it.gameMode = org.bukkit.GameMode.CREATIVE
                    it.teleport(Location(h.world, 0.0, 65.0, 0.0))
                    it.inventory.clear()
                }
                val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
                h.world.getBlockAt(0, 64, 0).type = Material.BARREL
                val barrel = (h.world.getBlockAt(0, 64, 0).state as org.bukkit.block.Barrel).inventory
                if (guard == "full" && viaBuilding) for (slot in 0 until barrel.size)
                    barrel.setItem(slot, ItemStack(Material.DIRT, 64))
                val worker = h.city.workers.first().copy(assignment = hall.id, nextDeliveryAt = 1,
                    items = List(CivsEquipment.SLOTS) { slot ->
                        if (guard == "full" && !viaBuilding && slot >= 6) CivResidentItem("DIRT", 64) else null
                    })
                val ready = h.city.copy(owner = player.uniqueId,
                    memberNames = mapOf(player.uniqueId.toString() to player.name),
                    buildings = listOf(hall, h.city.buildings.last()), workers = listOf(worker), stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
                h.start()
                fun request() = requireNotNull(if (viaBuilding) h.service.buildingRequest(player, hall.id)
                    else h.service.workerRequest(player, worker.id))
                val displayed = request()
                displayed shouldBe CivsBuildingRequest(Material.PAPER, 64, guard != "full" || viaBuilding)
                when (guard) {
                    "survival" -> {
                        player.gameMode = org.bukkit.GameMode.SURVIVAL
                        request() shouldBe displayed.copy(canGive = false)
                    }
                    "stale" -> {
                        barrel.setItem(0, ItemStack(Material.PAPER, 64))
                        request() shouldBe CivsBuildingRequest(Material.LEATHER, 64, true)
                    }
                }
                val before = requireNotNull(h.service.city(player))
                val nativeBefore = barrel.contents.map { it?.clone() }
                // An already-open dialog passes the material it displayed, not the new request.
                val issue = if (viaBuilding) h.service.giveBuildingRequest(player, hall.id, displayed.material.name)
                    else h.service.giveWorkerRequest(player, worker.id, displayed.material.name)
                if (guard == "full" && viaBuilding) h.await(issue) shouldBe Unit
                else (shouldThrow<CompletionException> { h.await(issue) }.cause as CivsRejection).reason shouldBe when {
                    guard == "full" -> "resident-inventory-full"
                    guard == "survival" && viaBuilding -> "construction-supplies-empty"
                    else -> "resident-item-missing"
                }
                h.service.city(player) shouldBe before
                h.service.city(player)!!.revision shouldBe before.revision
                h.service.cityBusy(player) shouldBe false
                barrel.contents.toList() shouldBe nativeBefore
                player.inventory.storageContents.filterNotNull().filterNot { it.type.isAir } shouldBe emptyList()
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.store.save(any()) }
            }
        }

    test("creative catalog slot and resident container click retain the exact native item through the journal") {
        serviceScenario(storageFactory = { CivsStorage(it, ru.arc.paper.playerstate.PaperPlayerDataPersistence { }) { emptyArray() } }) { h ->
            val nativeMenus = mutableListOf<org.bukkit.inventory.Inventory>()
            val player = object : org.mockbukkit.mockbukkit.entity.PlayerMock(h.paper.server, "CreativeNativeOwner") {
                override fun closeDialog() = Unit
                override fun openInventory(inventory: org.bukkit.inventory.Inventory): org.bukkit.inventory.InventoryView {
                    // A custom holder is not a BlockState snapshot. Preserve the actual
                    // menu instance and every native slot while supplying this missing getter.
                    val owner = inventory.holder
                    mockkObject(inventory)
                    every { inventory.getHolder(false) } returns owner
                    nativeMenus += inventory
                    return requireNotNull(super.openInventory(inventory))
                }
            }.also(h.paper.server::addPlayer).also {
                it.addAttachment(h.plugin, "arccivs.use", true)
                it.gameMode = org.bukkit.GameMode.CREATIVE
                it.teleport(Location(h.world, 0.0, 65.0, 0.0))
            }
            val worker = h.city.workers.first()
            val ready = h.city.copy(owner = player.uniqueId, memberNames = mapOf(player.uniqueId.toString() to player.name),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val pickaxe = ItemStack(Material.DIAMOND_PICKAXE).apply { editMeta {
                it.displayName(Component.text("Кирка шахтёра"))
                (it as org.bukkit.inventory.meta.Damageable).damage = 17
            } }
            // The fresh MockBukkit view has no slot conversion. Its concrete player view
            // provides Paper's five crafting slots and raw 36 -> native hotbar slot 0 mapping.
            val crafting = object : org.mockbukkit.mockbukkit.inventory.InventoryMock(player, 5, InventoryType.CRAFTING) {
                // This personal inventory has the same owner with or without block snapshots.
                override fun getHolder(useSnapshot: Boolean) = holder
            }
            val personalView = org.mockbukkit.mockbukkit.inventory.PlayerInventoryViewMock(player,
                crafting)
            player.openInventory(personalView)
            // Paper handles creative slot packets against inventoryMenu, never the open container menu.
            val creative = InventoryCreativeEvent(personalView, InventoryType.SlotType.QUICKBAR, 36, pickaxe.clone())
            h.paper.server.pluginManager.callEvent(creative)
            creative.isCancelled shouldBe false
            creative.click shouldBe ClickType.CREATIVE
            creative.slot shouldBe 0
            player.itemOnCursor.type.isAir shouldBe true
            player.inventory.setItem(creative.slot, creative.cursor) // Paper's accepted native-slot write.
            player.inventory.getItem(0) shouldBe pickaxe
            try {
                RequestDialog(h, player).use { dialog ->
                    dialog.ui.openWorker(player, ready.id, worker.id)
                    dialog.click(dialog.action("inventory"))
                    val container = player.openInventory
                    container.topInventory.size shouldBe CIVS_EQUIPMENT_SIZE
                    fun click() = InventoryClickEvent(container, InventoryType.SlotType.QUICKBAR,
                        CIVS_EQUIPMENT_SIZE + 27, ClickType.LEFT, InventoryAction.PICKUP_ALL).also {
                        h.paper.server.pluginManager.callEvent(it)
                    }
                    click().isCancelled shouldBe true
                    click().isCancelled shouldBe true
                    val tx = requireNotNull(receipt)
                    tx.residentTransfer!!.changes.single().before!!.stack() shouldBe pickaxe
                    player.inventory.getItem(0) shouldBe pickaxe
                    CivsEquipment.slots(h.service.city(player)!!.workers.single())[4] shouldBe null
                    val editWhilePending = InventoryCreativeEvent(personalView, InventoryType.SlotType.QUICKBAR, 36,
                        ItemStack(Material.TORCH, 64))
                    h.paper.server.pluginManager.callEvent(editWhilePending)
                    editWhilePending.isCancelled shouldBe true
                    journal.complete(tx); h.tick(); h.commit()
                    player.inventory.getItem(0) shouldBe null
                    player.itemOnCursor.type.isAir shouldBe true
                    CivsEquipment.slots(h.service.city(player)!!.workers.single())[4]!!.stack() shouldBe pickaxe
                    container.topInventory.getItem(civsEquipmentViewSlot(4)) shouldBe pickaxe
                    verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
                }
            } finally {
                nativeMenus.forEach { unmockkObject(it) }
            }
        }
    }

    test("worker and building requests prioritize a missing recipe ingredient before topping up another") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("ScribeSupplier").also { it.isOp = true; it.teleport(Location(h.world, 0.0, 65.0, 0.0)) }
            val hall = h.city.buildings.first()
            val worker = h.city.workers.first().copy(assignment = hall.id, nextDeliveryAt = 1)
            val ready = h.city.copy(owner = player.uniqueId, workers = listOf(worker), stock = Stock(),
                inventory = CivInventory(CivStorageMode.VIRTUAL, buildingMaterials = mapOf(hall.id.toString() to mapOf("PAPER" to 3L))))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.LEATHER, 16))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.start()
            h.service.workerRequest(player, worker.id) shouldBe CivsBuildingRequest(Material.LEATHER, 64, true)
            h.service.buildingRequest(player, hall.id) shouldBe CivsBuildingRequest(Material.LEATHER, 64, false)
        }
    }

    test("the carpenter barrel and resident request only the accepted batch ingredients") {
        serviceScenario { h ->
            val (workshop, carpenter) = h.prepareCarpentry()
            h.start()
            h.craftJob(carpenter.id).inputs shouldBe mapOf("OAK_LOG" to 1L)
            h.service.buildingRequest(h.player, workshop.id) shouldBe CivsBuildingRequest(Material.OAK_LOG, 1, false)
            h.service.workerRequest(h.player, carpenter.id) shouldBe CivsBuildingRequest(Material.OAK_LOG, 1, false)
            h.saves shouldBe emptyList()
        }
    }

    test("the carpenter barrel combines two accepted batches instead of counting one log twice") {
        serviceScenario { h ->
            val (workshop, first) = h.prepareCarpentry(mapOf("OAK_LOG" to 1L))
            val base = h.store.load().join().single()
            val mine = base.buildings.last().copy(type = BuildingType.QUARRY, production = null)
            val second = base.workers.last().copy(assignment = workshop.id)
            val miner = h.city.workers[2].copy(assignment = mine.id, nextDeliveryAt = 1)
            val ready = CivsRules.validate(base.copy(buildings = listOf(base.buildings.first(), workshop.copy(level = 2), mine),
                workers = listOf(first, second, miner)))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.start()
            h.craftJob(first.id).inputs shouldBe mapOf("OAK_LOG" to 1L)
            h.craftJob(second.id).inputs shouldBe mapOf("OAK_LOG" to 1L)
            h.service.workerRequest(h.player, first.id) shouldBe null
            h.service.workerRequest(h.player, second.id) shouldBe null
            h.service.buildingRequest(h.player, workshop.id) shouldBe CivsBuildingRequest(Material.OAK_LOG, 1, false)
            h.saves shouldBe emptyList()
        }
    }

    test("personal batch supplies wait for the journal and reduce the next request by the real donated amount") {
        serviceScenario(storageFactory = { CivsStorage(it, ru.arc.paper.playerstate.PaperPlayerDataPersistence { }) { emptyArray() } }) { h ->
            val player = h.paper.addPlayer("MasonSupplier").also { it.isOp = true; it.teleport(Location(h.world, 0.0, 65.0, 0.0)) }
            val mason = h.city.buildings.last().copy(type = BuildingType.STONEMASON, production = "STONE_BRICKS")
            val worker = h.city.workers.first().copy(assignment = mason.id, nextDeliveryAt = 1)
            val ready = h.city.copy(owner = player.uniqueId, buildings = listOf(h.city.buildings.first(), mason),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, 16))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.service.workerRequest(player, worker.id) shouldBe CivsBuildingRequest(Material.STONE, 64, true)
            val transfer = h.service.giveWorkerRequest(player, worker.id, "STONE")
            player.inventory.getItem(0)!!.amount shouldBe 16
            CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), "STONE") shouldBe 0
            journal.complete(requireNotNull(receipt)); h.tick()
            player.inventory.getItem(0) shouldBe null
            transfer.isDone shouldBe false
            h.commit(); h.tick(); transfer.join()
            val supplied = h.service.city(player)!!.workers.single()
            CivsEquipment.plainCount(supplied, "STONE") shouldBe 16
            h.service.workerRequest(player, worker.id) shouldBe CivsBuildingRequest(Material.STONE, 48, false)
            val produced = CivsRules.deliver(h.service.city(player)!!, player.uniqueId, worker.id, mason.id,
                supplied.completedCycles, supplied.nextDeliveryAt, h.time, outputHandled = true)
            CivsEquipment.plainCount(produced.workers.single(), "STONE") shouldBe 12
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("resident supply and return conserve native items through delayed city commits") {
        serviceScenario { h ->
            h.supplySite(h.owner, 1)
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val worker = h.city.workers.last().id
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.IRON_HELMET))
            val supplied = h.service.supplyWorker(h.player, worker, "IRON_HELMET")
            h.tick()
            supplied.isDone shouldBe false
            barrel.getItem(0) shouldBe null
            h.service.city(h.player)!!.workers.last().inventory shouldBe null
            h.commit(); supplied.join()
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.last(), "IRON_HELMET") shouldBe 1
            val returned = h.service.returnWorkerItem(h.player, worker, "IRON_HELMET")
            h.tick()
            barrel.getItem(0)!!.type shouldBe Material.IRON_HELMET
            h.commit(); returned.join()
            CivsEquipment.slots(h.service.city(h.player)!!.workers.last()).all { it == null } shouldBe true
            barrel.getItem(0)!!.amount shouldBe 1
        }
    }

    test("physical resident supplies queue each request and debit each durable receipt exactly once") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.BREAD, 24))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = h.city.buildings.mapIndexed { index, b -> if (index == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val journals = mutableListOf<Pair<CivInventoryTransaction, CompletableFuture<CivInventoryTransaction>>>()
            val retirements = mutableListOf<CompletableFuture<Boolean>>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture<CivInventoryTransaction>().also { journals += firstArg<CivInventoryTransaction>() to it }
            }
            every { h.store.deleteInventoryTransaction(any()) } answers {
                CompletableFuture<Boolean>().also { retirements += it }
            }
            h.start()
            val recruiting = h.service.supplyWorker(h.player, city.workers.first().id, "BREAD")
            barrel.getItem(0)!!.amount shouldBe 24
            h.saves.size shouldBe 0
            val queued = h.service.supplyWorker(h.player, city.workers.first().id, "BREAD")
            queued.isDone shouldBe false
            journals.size shouldBe 1
            journals.first().let { (receipt, durable) -> durable.complete(receipt) }; h.tick()
            barrel.getItem(0)!!.amount shouldBe 23
            h.service.city(h.player)!!.workers.size shouldBe 4
            h.saves.size shouldBe 1
            h.commit()
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "BREAD") shouldBe 1
            recruiting.isDone shouldBe false
            queued.isDone shouldBe false
            journals.size shouldBe 1
            retirements.single().complete(true); h.tick()
            recruiting.join() shouldBe Unit
            barrel.getItem(0)!!.amount shouldBe 23
            journals.size shouldBe 2
            journals.map { it.first.id }.distinct().size shouldBe 2
            journals.last().first.beforeRevision shouldBe journals.first().first.after.revision
            queued.isDone shouldBe false
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "BREAD") shouldBe 1
            journals.last().let { (receipt, durable) -> durable.complete(receipt) }; h.tick()
            barrel.getItem(0)!!.amount shouldBe 22
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "BREAD") shouldBe 1
            h.saves.size shouldBe 2
            h.commit()
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "BREAD") shouldBe 2
            queued.isDone shouldBe false
            retirements.size shouldBe 2
            retirements.last().complete(true); h.tick()
            queued.join() shouldBe Unit
            barrel.getItem(0)!!.amount shouldBe 22
            verify(exactly = 2) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
        }
    }
})

class CivsLostToolReceiptServiceTest : FunSpec({
    test("a lost local tool invalidates the rejected pickup without advancing its worker or mine") {
        serviceScenario(nativeGeometry = true) { h ->
            val quarry = spatialBuilding("caledonia", "miner", 2, CivPosition(30, 63, 0))
            val workers = h.city.workers.take(2).map { it.copy(assignment = quarry.id, nextDeliveryAt = 1,
                inventory = null, items = List(CivsEquipment.SLOTS) { null }) }
            val before = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), quarry),
                workers = workers, stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
            h.world.time = 1_000
            val control = requireNotNull(quarry.control)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, ItemStack(Material.IRON_PICKAXE))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val runtime = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }.set(runtime, { 0L })
            runtime.pulse(listOf(before))
            val selected = workers.associate { worker -> worker.id to requireNotNull(runtime.plan(before, worker)) }
            selected.values.forEach { work ->
                work.status shouldBe "waiting-tool"
                work.inputs shouldBe mapOf("IRON_PICKAXE" to 1L)
                work.changes shouldBe emptyList()
            }
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete(worker: CivWorker) = deliver.invokeService(h.service, before.id, worker.id, quarry.id,
                worker.completedCycles, worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            h.await(complete(workers.first()))
            barrel.getItem(0) shouldBe null
            val afterFirst = h.service.settlements().single()
            val winner = afterFirst.workers.first { it.id == workers.first().id }
            CivsEquipment.slots(winner)[4] shouldBe CivResidentItem("IRON_PICKAXE", 1)
            winner.completedCycles shouldBe workers.first().completedCycles + 1
            val waiting = afterFirst.workers.first { it.id == workers.last().id }
            waiting shouldBe workers.last()
            runtime.plan(afterFirst, waiting) shouldBe selected.getValue(waiting.id)
            val invalidated = mutableListOf<UUID>()
            mockkObject(runtime)
            try {
                every { runtime.invalidate(any(), any()) } answers { invalidated += firstArg<UUID>(); callOriginal() }
                val rejected = shouldThrow<CompletionException> { complete(waiting).join() }
                CivsDiagnostics.rejection(rejected)?.let { it.reason to it.values } shouldBe
                    ("materials-missing" to mapOf("material" to "IRON_PICKAXE", "count" to "1"))
                invalidated shouldBe listOf(waiting.id)
            } finally { unmockkObject(runtime) }
            h.service.settlements().single() shouldBe afterFirst
            runtime.plan(afterFirst, waiting) shouldBe null
            runtime.pulse(listOf(afterFirst))
            runtime.plan(afterFirst, waiting) shouldBe null
            runtime.idleReason(afterFirst, waiting) shouldBe "waiting-tool"
            afterFirst.minePlans shouldBe before.minePlans
            afterFirst.mineProgress shouldBe before.mineProgress
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }

            // A silent native write becomes visible to planning through the next bounded capture.
            barrel.setItem(0, ItemStack(Material.IRON_PICKAXE))
            captureServicePlanningStorage(h.service, afterFirst)
            runtime.pulse(listOf(afterFirst))
            val retry = requireNotNull(runtime.plan(afterFirst, waiting))
            retry.cycle shouldBe waiting.completedCycles
            retry.inputs shouldBe mapOf("IRON_PICKAXE" to 1L)
            h.await(complete(waiting))
            val afterBoth = h.service.settlements().single()
            barrel.getItem(0) shouldBe null
            afterBoth.workers.all { CivsEquipment.slots(it)[4] == CivResidentItem("IRON_PICKAXE", 1) } shouldBe true
            afterBoth.workers.first { it.id == waiting.id }.completedCycles shouldBe waiting.completedCycles + 1
            afterBoth.minePlans shouldBe before.minePlans
            afterBoth.mineProgress shouldBe before.mineProgress
            verify(exactly = 2) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

/** Real Core custom-click dispatch; only native dialog presentation is replaced in MockBukkit. */
private class RequestDialog(h: ServiceScenario, private val player: Player) : AutoCloseable {
    private lateinit var registration: Any
    lateinit var screen: PaperDialogScreen
        private set
    private val presenter: (Player, PaperDialogScreen, Any) -> Unit = { _, shown, registered -> screen = shown; registration = registered }
    private val runtime = PaperDialogRuntime::class.java.declaredConstructors.first { it.parameterCount == 2 }
        .also { it.isAccessible = true }.newInstance(h.plugin, presenter) as PaperDialogRuntime
    val ui = CivsUi(h.plugin, h.service, h.tasks, runtime, CivsText.fromYaml(civsLocaleSource()))

    fun action(id: String): String = registration.javaClass.declaredMethods.first { it.name.startsWith("key") }
        .also { it.isAccessible = true }.invoke(registration, id) as String

    fun click(action: String) {
        val connection = mockk<PlayerGameConnection> { every { player } returns this@RequestDialog.player }
        val response = mockk<DialogResponseView> {
            every { getText(any()) } returns null
            every { getFloat(any()) } returns null
        }
        runtime.onCustomClick(mockk {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(action)
            every { dialogResponseView } returns response
        })
    }

    override fun close() { ui.close(); runtime.close() }
}
