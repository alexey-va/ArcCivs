package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack

class CivsStorageServiceTest : FunSpec({
    test("home deposit validates native exact slots before publishing the RAM bag debit") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val worker = h.city.workers.first().copy(hasCollectedItems = true,
                items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("COAL", 8) else null })
            val before = h.city.copy(buildings = listOf(hall), workers = listOf(worker),
                inventory = null, autonomy = CivAutonomy(paused = true))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(0, 64, 0).state as org.bukkit.block.Barrel).inventory
            storage.persistCollectedNative = { _, _ -> error("Fresh home deposit requested native persistence") }
            val admission = CompletableFuture<CivInventoryTransaction>()
            val commit = CompletableFuture<Settlement>()
            var receipt: CivInventoryTransaction? = null
            var committing = false
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 1.5, 64.0, .5)
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); admission }
            every { h.store.completeInventoryTransaction(any()) } answers {
                firstArg<CivInventoryTransaction>() shouldBe receipt
                barrel.getItem(0) shouldBe ItemStack(Material.COAL, 8)
                committing = true
                commit
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val depositing = h.service.depositCollected(before.id, worker.id, hall.control)
            h.tickUntil("home deposit waiting for RAM admission") { receipt != null }
            barrel.isEmpty shouldBe true
            h.service.settlements().single() shouldBe before
            val admitted = requireNotNull(receipt)
            admission.complete(admitted)
            h.tickUntil("home deposit awaiting RAM commit") { committing }
            barrel.getItem(0) shouldBe ItemStack(Material.COAL, 8)
            h.service.settlements().single() shouldBe before
            depositing.isDone shouldBe false
            commit.complete(admitted.after)
            val saved = h.await(depositing)
            saved.stock shouldBe before.stock
            saved.inventory shouldBe null
            saved.workers.single().hasCollectedItems shouldBe false
            saved.workers.single().items!!.all { it == null } shouldBe true
            verify(exactly = 0) { h.store.save(any()) }
        }
    }

    test("queued warehouse deposit and withdrawal preserve items across unrelated saves") {
        serviceScenario { h ->
            val plan = h.supplySite(h.owner, 1)
            val original = h.store.load().join().single()
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(original.copy(
                workers = emptyList(), inventory = CivInventory(CivStorageMode.VIRTUAL))))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            every { h.player.location } returns Location(h.world, 10.0, 64.0, 0.0)
            h.start()
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.PAPER, 12))
            val member = h.paper.addPlayer("WarehouseMember")
            (member.openInventory as org.mockbukkit.mockbukkit.inventory.InventoryViewMock)
                .setTopInventory(h.paper.server.createInventory(member, 9))
            val membership = h.service.addMember(h.player, member.name)
            val deposit = h.service.depositMaterials(h.player, plan.building.id)
            deposit.isDone shouldBe false
            barrel.getItem(0)!!.amount shouldBe 12
            h.commit(); membership.join(); h.tick()
            if (deposit.isCompletedExceptionally) deposit.join()
            barrel.getItem(0) shouldBe null
            h.commit(); deposit.join()
            h.service.city(h.player)!!.inventory!!.buildingMaterials!![plan.building.id.toString()]!!["PAPER"] shouldBe 12L
            val another = h.paper.addPlayer("WarehouseSecondMember")
            (another.openInventory as org.mockbukkit.mockbukkit.inventory.InventoryViewMock)
                .setTopInventory(h.paper.server.createInventory(another, 9))
            val membership2 = h.service.addMember(h.player, another.name)
            val withdrawal = h.service.withdrawMaterial(h.player, plan.building.id, "PAPER")
            withdrawal.isDone shouldBe false
            h.commit(); membership2.join(); h.tick()
            barrel.getItem(0)!!.amount shouldBe 12
            h.commit(); withdrawal.join()
            h.service.city(h.player)!!.inventory!!.buildingMaterials.orEmpty().values.sumOf { it["PAPER"] ?: 0 } shouldBe 0L
            (member.uniqueId in h.service.city(h.player)!!.members) shouldBe true
        }
    }

    test("receipt bootstrap blocks drops before journal locks then releases only unrelated players while SQL is pending") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("BootstrapSupplier")
            val bystander = h.paper.addPlayer("BootstrapBystander")
            for (viewer in listOf(player, bystander)) {
                (viewer.openInventory as org.mockbukkit.mockbukkit.inventory.InventoryViewMock)
                    .setTopInventory(h.paper.server.createInventory(viewer, 9))
            }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 4)
            val city = h.store.load().join().single().copy(workers = emptyList())
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 4))
            val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
                city.copy(revision = city.revision + 1), listOf(CivInventorySlotChange(requireNotNull(plan.building.control),
                    "BARREL", 0, null, CivPlainItem("OAK_LOG", 4))),
                CivPlayerDebit(player.uniqueId, listOf(CivPlayerSlotChange(0, CivPlainItem("OAK_LOG", 4), null))))
            val local = CompletableFuture<List<CivInventoryTransaction>>()
            val canonical = CompletableFuture<List<Settlement>>()
            every { h.store.loadInventoryTransactions() } returns local
            every { h.store.load() } returns canonical
            every { h.store.deleteInventoryTransaction(receipt.id) } returns CompletableFuture.completedFuture(true)
            fun drop(target: Player) = h.paper.callEvent(org.bukkit.event.player.PlayerDropItemEvent(target, mockk(relaxed = true)))
            drop(player).isCancelled shouldBe true // The constructor closes the window even before start().
            val started = h.service.start(); h.tick()
            drop(player).isCancelled shouldBe true
            drop(bystander).isCancelled shouldBe true
            player.inventory.getItem(0)!!.amount shouldBe 4
            local.complete(listOf(receipt)); h.tick()
            started.isDone shouldBe false
            drop(player).isCancelled shouldBe true
            drop(bystander).isCancelled shouldBe false
            barrel.isEmpty shouldBe true
            canonical.complete(listOf(city)); h.tick(); started.join()
            h.paper.performTicks(20)
            player.inventory.getItem(0) shouldBe null
            barrel.getItem(0)!!.amount shouldBe 4
            h.saves.size shouldBe 1
            drop(player).isCancelled shouldBe true
            h.commit(); h.paper.performTicks(40)
            drop(player).isCancelled shouldBe false
            barrel.getItem(0)!!.amount shouldBe 4
            h.saves.size shouldBe 1
        }
    }

    test("known empty receipts release item changes independently of SQL failure and reload reacquires only the local bootstrap barrier") {
        serviceScenario(empty = true) { h ->
            val player = h.paper.addPlayer("BootstrapFree")
            val local = CompletableFuture<List<CivInventoryTransaction>>()
            val canonical = CompletableFuture<List<Settlement>>()
            every { h.store.loadInventoryTransactions() } returns local
            every { h.store.load() } returns canonical
            fun drop() = h.paper.callEvent(org.bukkit.event.player.PlayerDropItemEvent(player, mockk(relaxed = true)))
            val started = h.service.start(); h.tick()
            drop().isCancelled shouldBe true
            verify(exactly = 0) { h.store.load() }
            local.complete(emptyList()); h.tick()
            started.isDone shouldBe false
            drop().isCancelled shouldBe false
            canonical.completeExceptionally(IllegalStateException("SQL unavailable")); h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.unavailableReason() shouldBe "storage-unavailable"
            drop().isCancelled shouldBe false
            val reloadedReceipts = CompletableFuture<List<CivInventoryTransaction>>()
            every { h.store.loadInventoryTransactions() } returns reloadedReceipts
            every { h.store.load() } returns CompletableFuture.completedFuture(emptyList())
            val reloaded = h.service.reload(h.player)
            drop().isCancelled shouldBe true
            reloadedReceipts.complete(emptyList()); h.tick(); reloaded.join()
            drop().isCancelled shouldBe false
        }
    }

    test("a failed local receipt bootstrap never guesses an empty journal or unlocks a newly joined player") {
        serviceScenario(empty = true) { h ->
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.failedFuture(java.io.IOException("corrupt local receipt"))
            val started = h.service.start(); h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            h.service.unavailableReason() shouldBe "storage-unavailable"
            verify(exactly = 0) { h.store.load() }
            val player = h.paper.addPlayer("BootstrapJoined")
            h.paper.callEvent(org.bukkit.event.player.PlayerDropItemEvent(player, mockk(relaxed = true))).isCancelled shouldBe true
            h.paper.callEvent(org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe true
        }
    }

    test("physical mode materializes existing reserves only after its journal and does not repeat the transfer") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(0, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(0, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(96, 96, 24), CivStorageMode.VIRTUAL),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val journal = CompletableFuture<CivInventoryTransaction>()
            val retire = CompletableFuture<Boolean>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns retire
            h.start()
            val switched = h.service.setStorageMode(h.player, CivStorageMode.PHYSICAL)
            barrel.contents.filterNotNull().sumOf { it.amount } shouldBe 7
            h.service.city(h.player) shouldBe city
            journal.complete(receipt!!); h.tick()
            barrel.contents.filterNotNull().sumOf { it.amount } shouldBe 223
            barrel.getItem(0)!!.amount shouldBe 7
            h.commit()
            switched.isDone shouldBe false
            retire.complete(true); h.tick(); switched.join() shouldBe Unit
            h.service.city(h.player)!!.inventory!!.let {
                it.mode shouldBe CivStorageMode.PHYSICAL
                it.materials shouldBe emptyMap()
            }
            h.service.materializeStorage(h.player).join() shouldBe Unit
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            barrel.contents.filterNotNull().sumOf { it.amount } shouldBe 223
        }
    }

    test("full physical containers retain pending reserves without writing an empty journal") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(0, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(0, 63, 0).state as org.bukkit.block.Barrel).inventory
            for (slot in 0 until barrel.size) barrel.setItem(slot, org.bukkit.inventory.ItemStack(Material.STONE, 64))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(96, 96, 24)),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            h.service.materializeStorage(h.player).join() shouldBe Unit
            h.service.city(h.player) shouldBe city
            h.service.cityBusy(h.player) shouldBe false
            h.service.availableMaterials(h.player) shouldBe mapOf("STONE" to 1728L)
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.store.save(any()) }
        }
    }

    test("known receipt size rejection unlocks physical items without entering recovery") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(0, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(0, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.BREAD, 24))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL, mapOf("BREAD" to 1L)),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } returns CompletableFuture.failedFuture(CivsRejection("storage-transaction-too-large"))
            h.start()
            val rejected = h.service.materializeStorage(h.player); h.tick()
            (shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection).reason shouldBe "storage-transaction-too-large"
            h.service.cityBusy(h.player) shouldBe false
            h.service.ready() shouldBe true
            h.service.city(h.player) shouldBe city
            barrel.getItem(0)!!.amount shouldBe 24
            h.await(h.service.supplyWorker(h.player, city.workers.first().id, "BREAD"))
            val supplied = h.service.city(h.player)!!
            CivsEquipment.plainCount(supplied.workers.first(), "BREAD") shouldBe 1
            supplied.inventory shouldBe city.inventory
            barrel.getItem(0)!!.amount shouldBe 23
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()); h.store.completeLocalInventory(any()) }
            verify(exactly = 0) { h.store.save(any()); h.store.deleteInventoryTransaction(any()) }
        }
    }

    for (nativeApplied in listOf(false, true)) test("canonical committed inventory recovery reconciles nativeApplied=$nativeApplied without paying twice") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.BREAD, if (nativeApplied) 12 else 24))
            val before = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = h.city.buildings.mapIndexed { index, b -> if (index == 0) b.copy(control = control) else b })
            // A persisted pre-0.1.31 paid recruit receipt remains recoverable after the mechanic is removed.
            val after = before.copy(workers = before.workers + CivWorker(UUID.randomUUID(), "Recovered", null), revision = before.revision + 1)
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(control, "BARREL", 0, CivPlainItem("BREAD", 24), CivPlainItem("BREAD", 12))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(after))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns CompletableFuture.completedFuture(true)
            h.start(); h.paper.performTicks(25)
            h.service.city(h.player) shouldBe after
            barrel.getItem(0)!!.amount shouldBe 12
            h.saves.size shouldBe 0
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
        }
    }

    test("startup durably materializes full legacy cargo and still loads the other settlements") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 63, 0))
            val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, h.city.buildings.first().id,
                warehouse.entrance, "IRON_PICKAXE", 64)
            val worker = h.city.workers.first().copy(assignment = warehouse.id, cargo = cargo, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it >= CivsEquipment.BAG) CivResidentItem("STONE", 64) else null })
            val legacy = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), warehouse), workers = listOf(worker)))
            val other = h.city.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), workers = emptyList())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(legacy, other))
            val started = h.service.start(); h.tick()
            started.isDone shouldBe false
            h.service.ready() shouldBe false
            h.saves.single().first.workers.single().cargo!!.inventoryBacked shouldBe true
            h.commit(); started.join()
            h.service.ready() shouldBe true
            val loaded = h.service.city(h.player)!!
            CivsEquipment.plainCount(loaded.workers.single(), "IRON_PICKAXE") shouldBe 64
            CivsEquipment.plainCount(loaded.workers.single(), "STONE") shouldBe 27 * 64
            loaded.workers.single().items!!.size shouldBe 96
            h.service.corpseSettlements().size shouldBe 2
        }
    }

    test("startup fences recovered native writes before ready and admits ordinary read views after bootstrap") {
        serviceScenario { h ->
            val point = CivPosition(5, 63, 0)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(point.x, point.y, point.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.COBBLESTONE, 64))
            val before = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(point)))
            val after = before.copy(revision = before.revision + 1)
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(point, "BARREL", 0, null, CivPlainItem("COBBLESTONE", 64))))
            val receipts = CompletableFuture<List<CivInventoryTransaction>>()
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.loadInventoryTransactions() } returns receipts
            // The pinned fixture lacks getHolder(false); expose the real barrel's holder and location for this event.
            val eventInventory = mockk<org.bukkit.inventory.Inventory>(relaxed = true) {
                every { getHolder(false) } returns h.world.getBlockAt(point.x, point.y, point.z).state as org.bukkit.block.Barrel
                every { location } returns barrel.location
                every { size } returns 27
            }
            val view = mockk<org.bukkit.inventory.InventoryView>(relaxed = true)
            every { view.topInventory } returns eventInventory
            every { view.player } returns h.player
            fun assertWriteFence() {
                h.paper.callEvent(InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled shouldBe true
                h.paper.callEvent(org.bukkit.event.inventory.InventoryDragEvent(view, null,
                    ItemStack(Material.STONE, 2), false, mapOf(0 to ItemStack(Material.STONE)))).isCancelled shouldBe true
                h.paper.callEvent(org.bukkit.event.inventory.InventoryMoveItemEvent(eventInventory,
                    ItemStack(Material.COBBLESTONE), eventInventory, true)).isCancelled shouldBe true
            }
            val loaded = h.service.start()
            h.service.ready() shouldBe false
            loaded.isDone shouldBe false
            h.paper.callEvent(org.bukkit.event.inventory.InventoryOpenEvent(view)).isCancelled shouldBe true
            assertWriteFence()
            receipts.complete(listOf(tx))
            h.tick(); h.await(loaded) shouldBe Unit
            h.service.ready() shouldBe true
            h.saves.size shouldBe 0
            h.paper.callEvent(org.bukkit.event.inventory.InventoryOpenEvent(view)).isCancelled shouldBe false
            assertWriteFence()
            barrel.getItem(0)!!.amount shouldBe 64
        }
    }
})

class CivsNativeProductionParityTest : FunSpec({
    test("native workers can drain existing items while another migration reserve waits") {
        serviceScenario { h ->
            for (type in listOf(BuildingType.BUILDER_HUT, BuildingType.WAREHOUSE, BuildingType.FORESTER)) withClue(type) {
                val building = h.city.buildings.last().copy(type = type)
                val worker = h.city.workers.first().copy(assignment = building.id, nextDeliveryAt = 1)
                val physical = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                    buildings = listOf(h.city.buildings.first(), building), workers = listOf(worker))
                val automatic = CivsRules.validate(physical.copy(autonomy = CivAutonomy(),
                    inventory = physical.inventory!!.copy(materials = mapOf("OAK_LOG" to 64L))))
                CivsAutoStorageMigration.ready(automatic) shouldBe true
                for (city in listOf(physical, automatic)) {
                    h.service.productionReady(city, worker) shouldBe true
                    h.service.workInputsReady(city, worker) shouldBe true
                }
            }
        }
    }
})

class CivsAutonomousStorageRuntimeTest : FunSpec({
    test("a full city keeps its storage retry without delaying another city's reserve") {
        serviceScenario(nativeGeometry = true, storageFactory = { plugin -> CivsStorage(plugin) { emptyArray() } }) { h ->
            val firstPoint = CivPosition(0, 64, 0)
            val secondPoint = firstPoint.copy(x = 224)
            val hall = h.city.buildings.first().copy(control = firstPoint)
            val first = CivsRules.validate(h.city.copy(buildings = listOf(hall), workers = emptyList(), stock = Stock(),
                inventory = CivInventory(CivStorageMode.PHYSICAL, materials = mapOf("OAK_LOG" to 5L))))
            val secondHall = hall.copy(id = UUID.randomUUID(), anchor = secondPoint, entrance = secondPoint, control = secondPoint,
                bounds = CivBox(secondPoint.copy(x = 222, z = -2), secondPoint.copy(x = 226, y = 68, z = 2)))
            val second = CivsRules.validate(first.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), name = "Birch Town",
                center = secondPoint, buildings = listOf(secondHall),
                inventory = CivInventory(CivStorageMode.PHYSICAL, materials = mapOf("OAK_LOG" to 7L))))
            h.world.loadChunk(secondPoint.x shr 4, 0)
            fun barrel(point: CivPosition) = h.world.getBlockAt(point.x, point.y, point.z).let { block ->
                block.type = Material.BARREL
                (block.state as org.bukkit.block.Barrel).inventory
            }
            val full = barrel(firstPoint)
            val free = barrel(secondPoint)
            repeat(full.size) { full.setItem(it, ItemStack(Material.STONE, 64)) }
            h.paper.addPlayer("FirstTownVisitor").teleport(firstPoint.location(h.world))
            h.paper.addPlayer("SecondTownVisitor").teleport(secondPoint.location(h.world))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(first, second))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val flush = CivsStorageActions::class.java.getDeclaredMethod("pulseStorage").also { it.isAccessible = true }
            fun pulse() { flush.invokeService(h.service); h.tick() }
            pulse()
            h.saves shouldBe emptyList()
            pulse()
            h.saves.map { it.first.id } shouldBe listOf(second.id)
            free.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 7)
            h.commit()
            full.clear()
            h.time += 4_999
            pulse()
            h.saves.map { it.first.id } shouldBe listOf(second.id)
            full.getItem(0) shouldBe null
            h.time++
            pulse()
            h.saves.map { it.first.id } shouldBe listOf(second.id, first.id)
            full.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 5)
            free.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 7)
            h.commit()
        }
    }

    for (initiallyFull in listOf(false, true)) test("legacy Auto migrates once after startup and retries native full storage=$initiallyFull") {
        serviceScenario(nativeGeometry = true, storageFactory = { plugin -> CivsStorage(plugin) { emptyArray() } }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val before = CivsRules.validate(h.city.copy(buildings = listOf(hall), workers = emptyList(),
                inventory = null, autonomy = CivAutonomy(paused = true), stock = Stock(5, 3, 2)))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(0, 64, 0).state as org.bukkit.block.Barrel).inventory
            if (initiallyFull) repeat(barrel.size) { barrel.setItem(it, org.bukkit.inventory.ItemStack(Material.STONE, 64)) }
            h.paper.addPlayer("MigrationWitness").teleport(Location(h.world, 1.5, 65.0, .5))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.service.ready() shouldBe true
            val migrate = CivsStorageActions::class.java.getDeclaredMethod("pulseAutoStorage").also { it.isAccessible = true }
            val flush = CivsStorageActions::class.java.getDeclaredMethod("pulseStorage").also { it.isAccessible = true }
            migrate.invokeService(h.service)
            h.saves.size shouldBe 1
            val migrated = h.saves.single().first
            migrated.stock shouldBe Stock()
            migrated.inventory!!.materials shouldBe mapOf("OAK_LOG" to 5L, "COBBLESTONE" to 3L, "BREAD" to 2L)
            h.time += 6_000
            h.paper.performTicks(100)
            h.saves.size shouldBe 1 // An unknown migration save cannot issue another copy.
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.commit()
            flush.invokeService(h.service); h.tick()
            if (initiallyFull) {
                h.saves.size shouldBe 1
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
                barrel.clear()
                h.time += 6_000
                flush.invokeService(h.service); h.tick()
            }
            h.saves.size shouldBe 2
            fun count(material: Material) = barrel.contents.filterNotNull().filter { it.type == material }.sumOf { it.amount }
            count(Material.OAK_LOG) shouldBe 5
            count(Material.COBBLESTONE) shouldBe 3
            count(Material.BREAD) shouldBe 2
            h.time += 6_000
            h.paper.performTicks(100)
            h.saves.size shouldBe 2 // The same accepted slot receipt fences another flush.
            count(Material.BREAD) shouldBe 2
            h.commit()
            val flushed = h.saves.last().first
            CivsAutoStorageMigration.ready(flushed) shouldBe true
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(flushed))
            h.await(h.service.reload(h.player)) shouldBe Unit
            h.time += 6_000
            h.tick()
            h.saves.size shouldBe 2
            count(Material.OAK_LOG) shouldBe 5
            count(Material.COBBLESTONE) shouldBe 3
            count(Material.BREAD) shouldBe 2
        }
    }
})
