package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class CivsCourierServiceTest : FunSpec({
    test("courier pickup and unload publish each physical transfer once with cargo between them") {
        serviceScenario { h ->
            val home = h.city.buildings.last()
            val warehouse = home.copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 63, 0))
            val smith = home.copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH, anchor = CivPosition(20, 64, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), entrance = CivPosition(20, 64, -2),
                control = CivPosition(20, 63, 0), production = "IRON_PICKAXE")
            val ready = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), warehouse, smith), workers = h.city.workers.mapIndexed { index, worker ->
                    worker.copy(assignment = when (index) { 0 -> warehouse.id; 1 -> smith.id; else -> null }, nextDeliveryAt = 1)
                })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.world.loadChunk(1, 0)
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.world.getBlockAt(20, 63, 0).type = Material.BARREL
            val source = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            val target = (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
            source.setItem(0, org.bukkit.inventory.ItemStack(Material.IRON_INGOT, 3))
            source.setItem(1, org.bukkit.inventory.ItemStack(Material.COBBLESTONE, 2))
            val receipts = mutableListOf<CivInventoryTransaction>()
            every { h.store.beginLocalInventory(any(), capture(receipts)) } returns Unit
            h.start()
            val current = h.service.city(h.player)!!
            val plan = h.supplyPlan(current.workers.first().id)
            h.await(h.service.pickupCargo(current.id, current.workers.first().id, plan.cargo))
            source.getItem(0) shouldBe null
            target.isEmpty shouldBe true
            h.service.city(h.player)!!.workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
            h.service.pickupCargo(current.id, current.workers.first().id, plan.cargo).join()
            receipts.single().changes.single().container shouldBe warehouse.control
            h.await(h.service.dropoffCargo(current.id, current.workers.first().id, plan.cargo, smith.id))
            target.getItem(0)!!.amount shouldBe 3
            val finished = h.service.city(h.player)!!.workers.first()
            finished.cargo shouldBe null
            finished.completedCycles shouldBe 1L
            finished.skills!!["WAREHOUSE"] shouldBe 1L
            h.service.dropoffCargo(current.id, current.workers.first().id, plan.cargo, smith.id).isCompletedExceptionally shouldBe true
            receipts.size shouldBe 2
            receipts.last().beforeRevision shouldBe receipts.first().after.revision
            receipts.last().changes.single().container shouldBe smith.control
            h.saves shouldBe emptyList()
            source.getItem(1)!!.amount shouldBe 2
            target.getItem(0)!!.type shouldBe Material.IRON_INGOT
        }
    }

    test("another courier can take the same request after the first courier's bag fills") {
        serviceScenario { h ->
            val home = h.city.buildings.last()
            val warehouse = home.copy(type = BuildingType.WAREHOUSE, level = 2, control = CivPosition(10, 63, 0))
            val smith = home.copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH, anchor = CivPosition(20, 64, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), entrance = CivPosition(20, 64, -2),
                control = CivPosition(20, 63, 0), production = "IRON_PICKAXE")
            val ready = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), warehouse, smith), workers = h.city.workers.mapIndexed { index, worker ->
                    worker.copy(assignment = when (index) { 0, 1 -> warehouse.id; 2 -> smith.id; else -> null }, nextDeliveryAt = 1)
                }))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.world.loadChunk(1, 0)
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.world.getBlockAt(20, 63, 0).type = Material.BARREL
            val source = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            source.setItem(0, ItemStack(Material.IRON_INGOT, 3))
            h.start()
            val city = requireNotNull(h.service.city(h.player))
            val couriers = city.workers.filter { it.assignment == warehouse.id }
            h.tickUntil("one warehouse shipment is planned") { couriers.any { h.service.courierPlan(city, it) != null } }
            val (first, original) = couriers.mapNotNull { worker -> h.service.courierPlan(city, worker)?.let { worker to it } }.single()
            val second = couriers.single { it.id != first.id }
            val full = CivsEquipment.replace(first, CivsEquipment.slots(first).mapIndexed { slot, item ->
                if (slot >= CivsEquipment.BAG) CivResidentItem("DIRT", 64) else item
            })
            val current = city.copy(workers = city.workers.map { if (it.id == first.id) full else it })
            h.replaceCityInMemory(current)
            val at = h.time
            val refused = h.service.pickupCargo(city.id, first.id, original.cargo)
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(refused) })?.reason shouldBe "resident-inventory-full"
            verify(exactly = 0) { h.store.beginLocalInventory(any(), any()) }
            source.getItem(0) shouldBe ItemStack(Material.IRON_INGOT, 3)

            val replacement = h.supplyPlan(second.id)
            h.time shouldBe at
            replacement.cargo.copy(id = original.cargo.id) shouldBe original.cargo
            val saved = h.await(h.service.pickupCargo(city.id, second.id, replacement.cargo))
            verify(exactly = 1) { h.store.beginLocalInventory(any(), any()) }
            source.getItem(0) shouldBe null
            saved.workers.first { it.id == first.id } shouldBe full
            saved.workers.first { it.id == second.id }.cargo shouldBe replacement.cargo.copy(inventoryBacked = true)
        }
    }

    test("durable cargo with a removed source reserves only its surviving fallback destination") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val source = UUID.randomUUID()
            val cargo = CivCargo(UUID.randomUUID(), source, source, UUID.randomUUID(), CivPosition(10, 64, -2),
                "BREAD", 1, inventoryBacked = true)
            val worker = h.city.workers.first().copy(cargo = cargo,
                items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 1) else null })
            val ready = h.city.copy(stock = Stock(), buildings = listOf(hall), workers = listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.start(); h.tick()
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            dispatcher.plan(ready.id, worker.id)?.destinationBuildingId shouldBe hall.id
            dispatcher.plan(ready.id, worker.id)?.cargo shouldBe cargo
            dispatcher.failure(ready.id) shouldBe null
        }
    }

    test("a resumed supply capture retires when a city save begins instead of spinning the native queue") {
        serviceScenario { h ->
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))))
            h.start()
            val factory = CivsCargoRequests::class.java.getDeclaredMethod("captureSupply", UUID::class.java, Map::class.java)
                .also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val step = factory.invokeService(h.service, h.city.id, emptyMap<UUID, CivCourierPlan>()) as () -> CivSupplySnapshot?
            val member = h.paper.addPlayer("SupplyBusyMember")
            val save = h.service.addMember(h.player, member.name)
            save.isDone shouldBe false
            shouldThrow<CivsRejection> { step() }.reason shouldBe "city-busy"
            h.commit(); save.join()
            @Suppress("UNCHECKED_CAST")
            val retry = factory.invokeService(h.service, h.city.id, emptyMap<UUID, CivCourierPlan>()) as () -> CivSupplySnapshot?
            var result: CivSupplySnapshot? = null
            repeat(16) { if (result == null) result = retry() }
            result!!.cityId shouldBe h.city.id
        }
    }

    test("reload clears dispatcher requests published before their first periodic observation") {
        serviceScenario { h ->
            h.start()
            val current = requireNotNull(h.service.city(h.player))
            val changed = current.copy(revision = current.revision + 1, workers = current.workers.mapIndexed { index, worker ->
                if (index == 0) worker.copy(completedCycles = worker.completedCycles + 1) else worker
            })
            h.service.publish(changed).join()
            val dispatcher = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
                .get(h.service) as CivsSupplyDispatcher
            dispatcher.cityIds() shouldBe setOf(current.id)
            every { h.store.load() } returns CompletableFuture.completedFuture(emptyList())
            val reloaded = h.service.reload(h.player)
            dispatcher.cityIds() shouldBe emptySet()
            h.tick(); reloaded.join()
            h.service.city(h.player) shouldBe null
            dispatcher.cityIds() shouldBe emptySet()
        }
    }

    test("dispatcher ignores cosmetic revisions and refuses vanished stock before creating a pickup receipt") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 63, 0))
            val smith = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH,
                anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, -2), control = CivPosition(20, 63, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), production = "IRON_PICKAXE")
            val ready = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), warehouse, smith), workers = h.city.workers.take(2).mapIndexed { index, worker ->
                    worker.copy(assignment = if (index == 0) warehouse.id else smith.id, nextDeliveryAt = 1)
                })
            civSupplyInputs(ready.copy(name = "Another name", revision = 12,
                workers = ready.workers.map { it.copy(name = "Renamed", hunger = 4) })) shouldBe civSupplyInputs(ready)
            h.world.loadChunk(1, 0)
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.world.getBlockAt(20, 63, 0).type = Material.BARREL
            val source = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            source.setItem(0, ItemStack(Material.IRON_INGOT, 3))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.start()
            val plan = h.supplyPlan(ready.workers.first().id)
            source.clear() // External native writer with no inventory event, after async acceptance.
            h.service.pickupCargo(ready.id, ready.workers.first().id, plan.cargo).isCompletedExceptionally shouldBe true
            h.service.city(h.player)!!.workers.first().cargo shouldBe null
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "IRON_INGOT") shouldBe 0
            h.service.courierPlan(ready, ready.workers.first()) shouldBe null
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("producer returns fitting native output after checked admission and a courier then reads the workplace") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 63, 0))
            val forester = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, anchor = CivPosition(20, 64, 0),
                entrance = CivPosition(20, 64, -2), control = CivPosition(20, 63, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
            val producer = h.city.workers[1].copy(assignment = forester.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { when (it) {
                    4 -> CivResidentItem("IRON_AXE", 1)
                    6 -> CivResidentItem("OAK_LOG", 64)
                    7 -> CivResidentItem("OAK_SAPLING", 4)
                    8 -> CivResidentItem("BREAD", 16)
                    else -> null
                } })
            val ready = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), warehouse, forester),
                workers = listOf(h.city.workers.first().copy(assignment = warehouse.id, nextDeliveryAt = 1), producer))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.world.loadChunk(1, 0)
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.world.getBlockAt(20, 63, 0).type = Material.BARREL
            val source = (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
            val target = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            repeat(source.size) { source.setItem(it, ItemStack(Material.STONE, 64).apply {
                editMeta { meta -> meta.displayName(Component.text("Reserved fixture cargo")) }
            }) }
            source.setItem(0, ItemStack(Material.OAK_LOG, 60))
            every { h.store.beginLocalInventory(any(), any()) } throws CivsRejection("stale-city")
            h.start()
            val failed = h.service.returnResidentOutputs(ready.id, producer.id, forester.id)
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(failed) })?.reason shouldBe "stale-city"
            source.getItem(0)!!.amount shouldBe 60
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.last(), "OAK_LOG") shouldBe 64
            val receipts = mutableListOf<CivInventoryTransaction>()
            every { h.store.beginLocalInventory(any(), capture(receipts)) } returns Unit
            h.await(h.service.returnResidentOutputs(ready.id, producer.id, forester.id))
            receipts.single().changes.single().container shouldBe forester.control
            source.getItem(0)!!.amount shouldBe 64
            val deposited = h.service.city(h.player)!!
            CivsEquipment.plainCount(deposited.workers.last(), "OAK_LOG") shouldBe 60
            deposited.workers.last().items!!.take(6) shouldBe producer.items!!.take(6)
            deposited.workers.last().completedCycles shouldBe producer.completedCycles
            deposited.workers.last().nextDeliveryAt shouldBe producer.nextDeliveryAt
            val plan = h.supplyPlan(ready.workers.first().id)
            plan.cargo.sourceBuildingId shouldBe forester.id
            plan.cargo.sourceWorkerId shouldBe null
            plan.cargo.sourceEntrance shouldBe forester.entrance
            h.await(h.service.pickupCargo(ready.id, ready.workers.first().id, plan.cargo))
            val carried = h.service.city(h.player)!!
            CivsEquipment.plainCount(carried.workers.last(), "OAK_LOG") shouldBe 60
            CivsEquipment.plainCount(carried.workers.first(), "OAK_LOG") shouldBe plan.cargo.amount.toInt()
            h.await(h.service.dropoffCargo(ready.id, ready.workers.first().id, plan.cargo, warehouse.id))
            val finished = h.service.city(h.player)!!
            finished.workers.first().cargo shouldBe null
            CivsEquipment.plainCount(finished.workers.first(), "OAK_LOG") shouldBe 0
            target.getItem(0)!!.amount shouldBe plan.cargo.amount.toInt()
            source.getItem(0)?.amount shouldBe (64 - plan.cargo.amount).toInt().takeIf { it > 0 }
            verify(exactly = 0) { h.citizens.courierSupplySources(ready.id, any()) }
            verify(exactly = 0) { h.citizens.requireCargoPickupArrival(any(), any(), any()) }
            verify(atLeast = 1) { h.citizens.requireStorageArrival(ready.id, producer.id, forester.id) }
            verify(atLeast = 1) { h.citizens.requireStorageArrival(ready.id, ready.workers.first().id, forester.id) }
        }
    }

    test("a workplace return rechecks producer arrival after another city commit before its journal") {
        serviceScenario { h ->
            val forester = h.city.buildings.last().copy(type = BuildingType.FORESTER, control = CivPosition(10, 63, 0))
            val producer = h.city.workers.first().copy(assignment = forester.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("OAK_LOG", 64) else null })
            val ready = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), forester), workers = listOf(producer))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.start()
            val member = h.paper.addPlayer("ReturnWaitMember")
            val membership = h.service.addMember(h.player, member.name)
            val returning = h.service.returnResidentOutputs(ready.id, producer.id, forester.id)
            returning.isDone shouldBe false
            every { h.citizens.requireStorageArrival(ready.id, producer.id, forester.id) } throws CivsRejection("route-unreachable")
            h.commit(); membership.join(); h.tick()
            returning.isCompletedExceptionally shouldBe true
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.single(), "OAK_LOG") shouldBe 64
            (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory.isEmpty shouldBe true
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

class CivsAutoGlobalSupplyServiceTest : FunSpec({
    test("existing warehouse goods prevent another native grant to the requesting workplace") {
        lateinit var storage: CivsStorage
        serviceScenario(storageFactory = { CivsStorage(it) { emptyArray() }.also { storage = it } }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 64, 0))
            val workshop = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.SMELTERY, production = "STONE",
                anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, -2), control = CivPosition(20, 64, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
            val workers = h.city.workers.take(2).mapIndexed { index, worker ->
                worker.copy(assignment = if (index == 0) warehouse.id else workshop.id, nextDeliveryAt = 1)
            }
            val city = CivsRules.validate(h.city.copy(buildings = listOf(hall, warehouse, workshop), workers = workers,
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
            h.world.loadChunk(1, 0)
            for (x in listOf(0, 10, 20)) h.world.getBlockAt(x, 64, 0).type = Material.BARREL
            val source = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            repeat(6) { source.setItem(it, ItemStack(Material.COBBLESTONE, 64)) }
            val target = (h.world.getBlockAt(20, 64, 0).state as org.bukkit.block.Barrel).inventory
            target.setItem(0, ItemStack(Material.CHARCOAL, 1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.paper.addPlayer("AutoStockWitness").teleport(Location(h.world, 1.5, 65.0, .5))
            h.start()
            val field = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
            val dispatcher = field.get(h.service) as CivsSupplyDispatcher
            h.tickUntil("accepted native global observation") { dispatcher.observation(city.id) != null }
            dispatcher.plan(city.id, workers.first().id)!!.cargo.amount shouldBe 8L
            val pulse = CivsResidentSupplies::class.java.getDeclaredMethod("pulseAutoSupplies").also { it.isAccessible = true }
            h.time += 1_000
            pulse.invokeService(h.service)
            h.tick()
            target.contents.filterNotNull().filter { it.type == Material.COBBLESTONE }.sumOf { it.amount } shouldBe 0
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            // Dirty native generations cannot authorize an issue using the old accepted observation.
            source.clear()
            storage.supplyPickup(mockk { every { inventory } returns source })
            h.time += 1_000
            pulse.invokeService(h.service)
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.tickUntil("fresh empty warehouse observation") {
                dispatcher.observation(city.id)?.stores?.get(warehouse.id)?.stock?.isEmpty() == true
            }
            h.time += 1_000
            pulse.invokeService(h.service)
            h.tickUntil("exact globally missing grant") { h.saves.isNotEmpty() }
            target.contents.filterNotNull().filter { it.type == Material.COBBLESTONE }.sumOf { it.amount } shouldBe 8
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("an active physical Auto town captures bootstrap food stock before it has a warehouse") {
        serviceScenario(storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val worker = h.city.workers.first().copy(assignment = null, hunger = 0, homeId = hall.id)
            val city = CivsRules.validate(h.city.copy(buildings = listOf(hall), workers = listOf(worker),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val target = (h.world.getBlockAt(0, 64, 0).state as org.bukkit.block.Barrel).inventory
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.paper.addPlayer("AutoBootstrapWitness").teleport(Location(h.world, 1.5, 65.0, .5))
            h.start()
            CivsCargoRequests::class.java.getDeclaredMethod("pulseSupply").also { it.isAccessible = true }.invokeService(h.service)
            val field = CivsService::class.java.getDeclaredField("supply").also { it.isAccessible = true }
            val dispatcher = field.get(h.service) as CivsSupplyDispatcher
            h.tickUntil("bootstrap native observation") { dispatcher.observation(city.id) != null }
            h.time += 1_000
            CivsResidentSupplies::class.java.getDeclaredMethod("pulseAutoSupplies").also { it.isAccessible = true }.invokeService(h.service)
            h.tickUntil("bootstrap food receipt") { h.saves.isNotEmpty() }
            target.contents.filterNotNull().filter { it.type == Material.BREAD }.sumOf { it.amount } shouldBe 4
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

class CivsProducerReturnRecoveryTest : FunSpec({
    for (nativeAfter in listOf(false, true)) test("accepted workplace return recovers without an actor nativeAfter=$nativeAfter") {
        lateinit var storage: CivsStorage
        serviceScenario(storageFactory = { plugin -> CivsStorage(plugin) { emptyArray() }.also { storage = it } }) { h ->
            val forester = h.city.buildings.last().copy(type = BuildingType.FORESTER, control = CivPosition(10, 63, 0))
            val worker = h.city.workers.first().copy(assignment = forester.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("OAK_LOG", 64) else null })
            val before = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), forester), workers = listOf(worker)))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
            barrel.setItem(0, ItemStack(Material.OAK_LOG, 60))
            val id = UUID.randomUUID()
            storage.reserve(before, id)
            val prepared = storage.prepareResidentReturn(before, worker.id, forester.id)
            val receipt = CivInventoryTransaction(id, before.id, before.revision,
                before.copy(workers = listOf(prepared.worker), inventory = prepared.plan.inventoryAfter,
                    revision = before.revision + 1), prepared.plan.changes).validated()
            storage.seal(receipt)
            storage.unlock(id)
            if (nativeAfter) barrel.setItem(0, ItemStack(Material.OAK_LOG, 64))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            every { h.citizens.courierSupplySources(any(), any()) } returns emptyMap()
            h.start()
            h.tickUntil("recovered workplace return") { h.saves.isNotEmpty() }
            h.saves.single().first shouldBe receipt.after
            barrel.getItem(0)!!.amount shouldBe 64
            h.commit()
            val recovered = h.service.city(h.player)!!
            recovered shouldBe receipt.after
            CivsEquipment.plainCount(recovered.workers.single(), "OAK_LOG") shouldBe 60
            recovered.workers.single().completedCycles shouldBe worker.completedCycles
            recovered.stock shouldBe Stock()
            verify(exactly = 0) { h.citizens.requireStorageArrival(any(), any(), any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
        }
    }
})

class CivsAutonomousDepotFreshnessTest : FunSpec({
    test("unload rejects a changed earlier warehouse before saving goods at an unchanged destination") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val first = h.city.buildings.last().copy(id = UUID(1, 10), type = BuildingType.WAREHOUSE,
                control = CivPosition(10, 64, 0))
            val second = first.copy(id = UUID(1, 20), anchor = CivPosition(20, 64, 0),
                entrance = CivPosition(20, 64, -2), control = CivPosition(20, 64, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
            val cargo = CivCargo(UUID.randomUUID(), second.id, UUID.randomUUID(), second.id,
                CivPosition(30, 64, 0), "STONE", 64, sourceWorkerId = UUID.randomUUID(), stockResource = Resource.STONE)
            val courier = h.city.workers.first().copy(assignment = second.id, nextDeliveryAt = 1, cargo = cargo)
            val buildings = listOf(hall, first, second)
            val city = CivsRules.validate(h.city.copy(buildings = buildings, workers = listOf(courier),
                inventory = null, stock = Stock(wood = 64), autonomy = CivAutonomy(storage = buildings.associate {
                    it.id.toString() to CivAutonomousStore(if (it.id == hall.id) 27 else 1, 27, 1)
                })))
            h.world.loadChunk(1, 0)
            for (x in listOf(0, 10, 20)) h.world.getBlockAt(x, 64, 0).type = Material.BARREL
            val firstBarrel = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            val secondBarrel = (h.world.getBlockAt(20, 64, 0).state as org.bukkit.block.Barrel).inventory
            for (slot in 0 until 26) {
                firstBarrel.setItem(slot, ItemStack(Material.COBBLESTONE, 64))
                secondBarrel.setItem(slot, ItemStack(Material.COBBLESTONE, 64))
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            val capture = storage.startSupplyCapture(city)
            repeat(3) { capture.step() }
            storage.rememberAutonomousCapacity(city, requireNotNull(capture.result))
            storage.autonomousCapacityCurrent(city, second.id) shouldBe true
            firstBarrel.setItem(26, ItemStack(Material.COBBLESTONE, 64))
            storage.supplyPickup(mockk { every { inventory } returns firstBarrel })
            storage.autonomousCapacityCurrent(city, second.id) shouldBe true
            val result = h.service.dropoffCargo(city.id, courier.id, cargo, second.id)
            h.tickUntil("depot admission") { result.isDone || h.saves.isNotEmpty() }
            h.saves shouldBe emptyList()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason shouldBe "storage-observation-stale"
            h.service.settlements().single().workers.single().cargo shouldBe cargo
        }
    }
})

class CivsAutoSupplyAckAuthorityTest : FunSpec({
    test("a revoked Lands grant after the journal ACK issues no native items and preserves recovery ownership") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val worker = h.city.workers.first().copy(homeId = hall.id, hunger = 0)
            val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                autonomy = CivAutonomy(), buildings = listOf(hall), workers = listOf(worker)))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(0, 64, 0).state as org.bukkit.block.Barrel).inventory
            var allowed = true
            every { h.worldAdapter.canModify(any(), any(), any()) } answers { allowed }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val ack = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); ack }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.paper.addPlayer("GrantWitness").teleport(Location(h.world, 1.5, 65.0, .5))
            h.start()
            val observation = CivsResidentSupplies::class.java.getDeclaredMethod("autoSupplyObservation", Settlement::class.java)
                .also { it.isAccessible = true }
            h.tickUntil("native Auto supply observation") { observation.invokeService(h.service, city) != null }
            val pulse = CivsResidentSupplies::class.java.getDeclaredMethod("pulseAutoSupplies").also { it.isAccessible = true }
            h.time += 1_000
            pulse.invokeService(h.service)
            h.tickUntil("Auto supply journal admission") { receipt != null }
            requireNotNull(receipt).autoSupplyBuildingId shouldBe hall.id
            barrel.isEmpty shouldBe true
            allowed = false
            ack.complete(receipt!!)
            h.tickUntil("blocked grant recovery") { h.service.debugState("overview", city.id, null)["blocked_recovery_total"] == 1 }
            barrel.isEmpty shouldBe true
            h.saves shouldBe emptyList()
            h.service.settlements().single() shouldBe city
            h.time += 10_000; pulse.invokeService(h.service)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }
})
