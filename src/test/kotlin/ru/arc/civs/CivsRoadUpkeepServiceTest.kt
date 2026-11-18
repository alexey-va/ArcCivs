package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.chunk.PaperChunkKey

class CivsRoadUpkeepServiceTest : FunSpec({
    test("Auto road upkeep waits for the observed builder and its exact journal before spending one stone") {
        serviceScenario { h ->
            val upkeep = ServiceRoadUpkeep(h)
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            upkeep.publish()
            every { h.citizens.requireWorldWorkArrival(upkeep.city.id, upkeep.worker.id, upkeep.work) } throws
                CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { upkeep.deliver().join() })?.reason shouldBe "route-unreachable"
            receipt shouldBe null
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.DIRT
            every { h.citizens.requireWorldWorkArrival(upkeep.city.id, upkeep.worker.id, upkeep.work) } returns Unit
            val result = upkeep.deliver()
            val tx = requireNotNull(receipt)
            tx.upkeep!!.work shouldBe upkeep.work
            tx.worldChanges shouldBe upkeep.work.changes
            tx.changes shouldBe emptyList()
            tx.after.stock shouldBe upkeep.city.stock.copy(stone = upkeep.city.stock.stone - 1)
            tx.after.workers.single().copy(completedCycles = upkeep.worker.completedCycles,
                nextDeliveryAt = upkeep.worker.nextDeliveryAt) shouldBe upkeep.worker
            h.service.settlements().single().stock shouldBe upkeep.city.stock
            h.saves shouldBe emptyList()
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.DIRT
            result.isDone shouldBe false
            journal.complete(tx)
            h.tickUntil("road patch after its journal") { h.saves.isNotEmpty() }
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.COBBLESTONE
            h.service.settlements().single().stock shouldBe upkeep.city.stock
            h.commit()
            result.join().stock shouldBe tx.after.stock
            upkeep.deliver().isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.citizens.requireConstructionArrival(any(), any(), any()) }
        }
    }

    test("native road authority revoked after journal acknowledgement prevents the patch and city credit") {
        serviceScenario { h ->
            val upkeep = ServiceRoadUpkeep(h)
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            h.start()
            upkeep.publish()
            val result = upkeep.deliver()
            val tx = requireNotNull(receipt)
            every { h.worldAdapter.validateRoadUpkeep(any(), upkeep.work) } throws CivsRejection("lands-denied")
            journal.complete(tx)
            h.tickUntil("upkeep authority rejection") { result.isDone }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason shouldBe "inventory-conflict"
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.DIRT
            h.service.settlements().single().stock shouldBe upkeep.city.stock
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("a committed upkeep receipt recovers its native patch without requiring spare stock or a new actor arrival") {
        mockkConstructor(CivsFootTraffic::class)
        try {
            every { anyConstructed<CivsFootTraffic>().registerSurface(any(), any()) } returns Unit
            serviceScenario { h ->
                val upkeep = ServiceRoadUpkeep(h)
                val reserve = CivsAutonomy.nextGoal(upkeep.city)?.cost?.stone ?: 0L
                val before = upkeep.city.copy(stock = upkeep.city.stock.copy(stone = reserve + 1))
                val after = CivsRoadMaintenance.complete(before, upkeep.work, h.time)
                val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after, emptyList(),
                    worldChanges = upkeep.work.changes, upkeep = CivRoadUpkeepReceipt(upkeep.worker, before.stock, upkeep.work, h.time)).validated()
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(after))
                every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
                every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
                h.start()
                h.tickUntil("recovered committed road patch") { h.world.getBlockAt(20, 63, 0).type == Material.COBBLESTONE }
                h.tick()
                h.service.settlements().single().stock.stone shouldBe reserve
                h.service.settlements().single().workers.single().completedCycles shouldBe 1L
                h.saves shouldBe emptyList()
                verify(exactly = 0) { h.citizens.requireWorldWorkArrival(any(), any(), any()) }
                verify(exactly = 1) { h.worldAdapter.validateRoadUpkeep(before, upkeep.work) }
                verify(exactly = 1) { anyConstructed<CivsFootTraffic>().registerSurface(after.id, upkeep.work.changes.single().position) }
            }
        } finally { unmockkConstructor(CivsFootTraffic::class) }
    }

    for ((modelAfter, worldAfter, slotAfter) in listOf(
        Triple(true, true, true), Triple(true, false, true), Triple(true, true, false), Triple(false, true, true)))
    test("road recovery retires revoked authority only for committed model and applied endpoints model=$modelAfter world=$worldAfter slot=$slotAfter") {
        serviceScenario { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            val before = upkeep.city
            val after = CivsRoadMaintenance.complete(before, upkeep.work, h.time, nativePaid = true)
            val control = requireNotNull(before.building(upkeep.work.buildingId)?.control)
            val debit = CivInventorySlotChange(control, "BARREL", 0, CivPlainItem("COBBLESTONE", 1), null)
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after, listOf(debit),
                worldChanges = upkeep.work.changes,
                upkeep = CivRoadUpkeepReceipt(upkeep.worker, before.stock, upkeep.work, h.time)).validated()
            val inventory = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Container).inventory
            if (!slotAfter) inventory.setItem(0, ItemStack(Material.COBBLESTONE, 1))
            if (worldAfter) h.world.getBlockAt(20, 63, 0).type = Material.COBBLESTONE
            val loaded = if (modelAfter) after else before
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(loaded))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
            every { h.worldAdapter.validateRoadUpkeep(any(), upkeep.work) } throws CivsRejection("lands-denied")
            h.start()
            val fullyApplied = modelAfter && worldAfter && slotAfter
            h.tickUntil("road receipt retirement or retained authority fence") {
                val state = h.service.debugState("overview", before.id, null)
                if (fullyApplied) state["inventory_recovery_total"] == 0 else state["blocked_recovery_total"] == 1
            }
            h.tick()
            h.service.settlements().single() shouldBe loaded
            h.saves shouldBe emptyList()
            h.world.getBlockAt(20, 63, 0).type shouldBe if (worldAfter) Material.COBBLESTONE else Material.DIRT
            inventory.getItem(0)?.let(CivResidentItem::of) shouldBe if (slotAfter) null else CivResidentItem("COBBLESTONE", 1)
            verify(exactly = if (fullyApplied) 1 else 0) { h.store.deleteInventoryTransaction(tx.id) }
            verify(exactly = if (fullyApplied) 0 else 1) { h.worldAdapter.validateRoadUpkeep(any(), upkeep.work) }
            verify(exactly = 0) { h.citizens.requireWorldWorkArrival(any(), any(), any()) }
        }
    }
})

class CivsUpkeepSupplyBufferServiceTest : FunSpec({
    test("a selected native upkeep job buffers sixteen real hut cobblestone without changing its one item payment") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            h.start(); upkeep.publish()
            h.service.upkeepSupplyRequests(upkeep.city) { emptyMap() } shouldBe emptyList()
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            maintenance.plan(upkeep.city, upkeep.worker) shouldBe upkeep.work
            val target = requireNotNull(upkeep.worker.assignment)
            h.service.upkeepRequirements(upkeep.city) shouldBe mapOf(upkeep.worker.id to mapOf("COBBLESTONE" to 1L))
            for ((local, deficit) in listOf(0L to 16L, 7L to 9L, 16L to 0L, 64L to 0L)) {
                h.service.upkeepSupplyRequests(upkeep.city) { mapOf("COBBLESTONE" to local) } shouldBe
                    if (deficit == 0L) emptyList() else listOf(CivSupplyRequest(target, "COBBLESTONE", deficit, 0))
            }
            h.service.workInputsReady(upkeep.city, upkeep.worker) shouldBe false
            val own = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            own.setItem(0, ItemStack(Material.COBBLESTONE, 1))
            captureServicePlanningStorage(h.service, upkeep.city)
            h.service.workInputsReady(upkeep.city, upkeep.worker) shouldBe true
            own.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 1)
            h.saves shouldBe emptyList()
            maintenance.invalidate(upkeep.worker.id)
            h.service.upkeepSupplyRequests(upkeep.city) { emptyMap() } shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("upkeep buffer subtracts carried native supplies and counts an exact incoming hut shipment once") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true, cobblestone = 4)
            h.start(); upkeep.publish()
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            maintenance.plan(upkeep.city, upkeep.worker) shouldBe upkeep.work
            val target = requireNotNull(upkeep.worker.assignment)
            val warehouse = upkeep.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                level = 1, anchor = CivPosition(40, 64, 0), entrance = CivPosition(40, 64, -2),
                bounds = CivBox(CivPosition(38, 63, -2), CivPosition(42, 68, 2)), control = CivPosition(40, 64, 0))
            val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, target, warehouse.entrance,
                "COBBLESTONE", 5, inventoryBacked = true)
            val courier = CivsEquipment.replace(CivWorker(UUID.randomUUID(), "Courier", warehouse.id, cargo = cargo),
                List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 5) else null })
            val current = upkeep.city.copy(buildings = upkeep.city.buildings + warehouse,
                workers = upkeep.city.workers + courier)
            h.service.upkeepSupplyRequests(current) { mapOf("COBBLESTONE" to 3L) } shouldBe
                listOf(CivSupplyRequest(target, "COBBLESTONE", 9, 5))
            h.service.upkeepSupplyRequests(current) { mapOf("COBBLESTONE" to 20L) } shouldBe
                listOf(CivSupplyRequest(target, "COBBLESTONE", 0, 5))
            CivsEquipment.usableCount(current.workers.first(), "COBBLESTONE") shouldBe 4
            courier.cargo shouldBe cargo
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("buffered upkeep delivery is bounded by available real source stock bag and native destination room") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            h.start(); upkeep.publish()
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            maintenance.plan(upkeep.city, upkeep.worker) shouldBe upkeep.work
            val target = requireNotNull(upkeep.worker.assignment)
            val requests = h.service.upkeepSupplyRequests(upkeep.city) { emptyMap() }
            val source = UUID.randomUUID()
            val courier = UUID.randomUUID()
            for ((available, room, bag, expected) in listOf(listOf(64L, 64L, 64L, 16L),
                listOf(4L, 64L, 64L, 4L), listOf(64L, 3L, 64L, 3L), listOf(64L, 64L, 2L, 2L),
                listOf(0L, 64L, 64L, 0L), listOf(64L, 0L, 64L, 0L))) {
                val warehouse = CivSupplyStore(source, CivPosition(0, 64, 0), CivPosition(0, 64, 0),
                    mapOf("COBBLESTONE" to available), emptyMap(), null, CivSupplyCapacity(1, emptyMap()), BuildingType.WAREHOUSE)
                val hut = CivSupplyStore(target, CivPosition(10, 64, 0), CivPosition(10, 64, 0), emptyMap(),
                    emptyMap(), null, CivSupplyCapacity(0, mapOf("COBBLESTONE" to room)), BuildingType.BUILDER_HUT)
                val snapshot = CivSupplySnapshot(upkeep.city.id, Unit, mapOf(source to warehouse, target to hut), requests,
                    listOf(CivSupplyCourier(courier, source, 0, warehouse.entrance, mapOf("COBBLESTONE" to bag))),
                    emptyList(), emptyMap(), mapOf("COBBLESTONE" to 64))
                val plans = allocateCivSupplies(snapshot).plans.values
                plans.sumOf { it.cargo.amount } shouldBe expected
                plans.forEach { it.cargo.sourceBuildingId shouldBe source; it.destinationBuildingId shouldBe target }
            }
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

class CivsUpkeepResidentPickupServiceTest : FunSpec({
    test("an empty upkeep bag visits storage and a durable native pickup pays exactly one road repair") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            val worker = CivsEquipment.collectSupplies(upkeep.worker, mapOf("BREAD" to 3L))
            val city = upkeep.city.copy(workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.worldAdapter.captureRoadUpkeep(any(), any(), any(), any()) } answers {
                upkeep.work.takeIf { h.world.getBlockAt(20, 63, 0).type == Material.DIRT }
            }
            val ack = CompletableFuture<CivInventoryTransaction>()
            val receipts = mutableListOf<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                val tx = firstArg<CivInventoryTransaction>().also(receipts::add)
                if (receipts.size == 1) ack else CompletableFuture.completedFuture(tx)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start(); upkeep.publish()
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            maintenance.plan(city, worker) shouldBe upkeep.work
            h.service.workInputsReady(city, worker) shouldBe false
            h.service.upkeepSupplyRequests(city) { emptyMap() } shouldBe
                listOf(CivSupplyRequest(worker.assignment!!, "COBBLESTONE", 16, 0))
            val plan = CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, null),
                CivPosition(10, 64, 0), upkeep.work.standing, BuildingType.BUILDER_HUT, upkeep.work.durationMillis,
                null, completeAtWorkplace = true, worldWork = upkeep.work, suppliesNeeded = true)
            val cycle = plan.cycle()
            cycle.start()
            cycle.phase shouldBe CitizenPhase.TO_STORAGE
            // The ordinary courier's existing buffer arrives after this selected job starts its storage trip.
            val barrel = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, ItemStack(Material.COBBLESTONE, 64))
            barrel.setItem(1, ItemStack(Material.BREAD, 5))
            val pickup = h.service.prepareResidentSupplies(city.id, worker.id, worker.assignment!!)
            h.tickUntil("upkeep pickup journal") { receipts.isNotEmpty() }
            receipts.single().changes.single() shouldBe CivInventorySlotChange(CivPosition(10, 64, 0), "BARREL", 0,
                CivPlainItem("COBBLESTONE", 64), CivPlainItem("COBBLESTONE", 48))
            barrel.getItem(0)!!.amount shouldBe 64
            CivsEquipment.usableCount(h.service.settlements().single().workers.single(), "COBBLESTONE") shouldBe 0
            h.saves shouldBe emptyList()
            ack.complete(receipts.single())
            h.tickUntil("upkeep native pickup") { h.saves.isNotEmpty() }
            h.commit()
            val supplied = pickup.join()
            val carrying = supplied.workers.single()
            CivsEquipment.usableCount(carrying, "COBBLESTONE") shouldBe 16
            CivsEquipment.usableCount(carrying, "BREAD") shouldBe 3
            barrel.getItem(0)!!.amount shouldBe 48
            barrel.getItem(1)!!.amount shouldBe 5
            carrying.hunger shouldBe worker.hunger
            carrying.life shouldBe worker.life
            supplied.stock shouldBe city.stock
            supplied.construction shouldBe null
            h.service.workInputsReady(supplied, carrying) shouldBe true
            cycle.arrive(h.time)
            cycle.phase shouldBe CitizenPhase.TO_WORK
            cycle.arrive(h.time)
            cycle.phase shouldBe CitizenPhase.WORKING
            for (elapsed in 1_000L..upkeep.work.durationMillis step 1_000L) cycle.work(h.time + elapsed)
            cycle.work(h.time + upkeep.work.durationMillis)
            cycle.phase shouldBe CitizenPhase.READY
            val repair = upkeep.deliver()
            h.tickUntil("paid upkeep repair") { h.saves.size == 2 }
            h.commit()
            val repaired = repair.join()
            receipts.last().upkeep!!.work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            receipts.last().changes shouldBe emptyList()
            CivsEquipment.usableCount(repaired.workers.single(), "COBBLESTONE") shouldBe 15
            CivsEquipment.usableCount(repaired.workers.single(), "BREAD") shouldBe 3
            repaired.workers.single().hunger shouldBe worker.hunger
            repaired.workers.single().life shouldBe worker.life
            repaired.stock shouldBe city.stock
            repaired.construction shouldBe null
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.COBBLESTONE
            barrel.getItem(0)!!.amount + CivsEquipment.usableCount(repaired.workers.single(), "COBBLESTONE") + 1 shouldBe 64
            verify(atLeast = 1) { h.citizens.requireStorageArrival(city.id, worker.id, worker.assignment!!) }
        }
    }

    for ((carried, available, picked) in listOf(Triple(0, 1, 1), Triple(14, 64, 2), Triple(16, 64, 0)))
    test("upkeep pickup respects real stock and the existing sixteen item buffer: carried=$carried available=$available") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true, cobblestone = carried)
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start(); upkeep.publish()
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            maintenance.plan(upkeep.city, upkeep.worker) shouldBe upkeep.work
            val barrel = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, ItemStack(Material.COBBLESTONE, available))
            val pickup = h.service.prepareResidentSupplies(upkeep.city.id, upkeep.worker.id, upkeep.worker.assignment!!)
            if (picked > 0) {
                h.tickUntil("bounded upkeep pickup") { h.saves.isNotEmpty() }
                h.commit()
            }
            val after = h.await(pickup)
            CivsEquipment.usableCount(after.workers.single(), "COBBLESTONE") shouldBe carried + picked
            barrel.getItem(0)?.amount shouldBe (available - picked).takeIf { it > 0 }
            after.stock shouldBe upkeep.city.stock
            after.construction shouldBe null
            verify(exactly = if (picked > 0) 1 else 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("an empty hut never invents upkeep stock and retains its ordinary courier demand") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            h.start(); upkeep.publish()
            val maintenance = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
                .get(h.service) as CivsRoadMaintenance
            maintenance.plan(upkeep.city, upkeep.worker) shouldBe upkeep.work
            val after = h.await(h.service.prepareResidentSupplies(upkeep.city.id, upkeep.worker.id, upkeep.worker.assignment!!))
            after shouldBe upkeep.city
            h.service.upkeepSupplyRequests(after) { emptyMap() } shouldBe
                listOf(CivSupplyRequest(upkeep.worker.assignment!!, "COBBLESTONE", 16, 0))
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("an ordinary builder without a selected upkeep job still rejects pickup without construction") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            h.start(); upkeep.publish()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                h.service.prepareResidentSupplies(upkeep.city.id, upkeep.worker.id, upkeep.worker.assignment!!).join()
            })?.reason shouldBe "no-construction"
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("upkeep pickup retains the ordinary observed storage arrival guard") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            h.start(); upkeep.publish()
            every { h.citizens.requireStorageArrival(any(), any(), any()) } throws CivsRejection("worker-too-far")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                h.service.prepareResidentSupplies(upkeep.city.id, upkeep.worker.id, upkeep.worker.assignment!!).join()
            })?.reason shouldBe "worker-too-far"
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

class CivsNativeRoadUpkeepTest : FunSpec({
    test("physical Auto builder repairs only after its carried native payment journal is acknowledged") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true, cobblestone = 1)
            val ack = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); ack }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start(); upkeep.publish()
            h.service.upkeepRequirements(upkeep.city) shouldBe emptyMap()
            val result = upkeep.deliver()
            val tx = requireNotNull(receipt)
            tx.upkeep!!.work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            tx.changes shouldBe emptyList()
            tx.after.stock shouldBe Stock()
            CivsEquipment.usableCount(tx.after.workers.single(), "COBBLESTONE") shouldBe 0
            CivsEquipment.usableCount(h.service.settlements().single().workers.single(), "COBBLESTONE") shouldBe 1
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.DIRT
            h.saves shouldBe emptyList()
            ack.complete(tx)
            h.tickUntil("native paid road patch") { h.saves.isNotEmpty() }
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.COBBLESTONE
            h.commit()
            result.join().stock shouldBe Stock()
            CivsEquipment.usableCount(h.service.settlements().single().workers.single(), "COBBLESTONE") shouldBe 0
            upkeep.deliver().isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a captured native road job requests a real hut buffer and waits for one item payment without closed stone") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val upkeep = ServiceRoadUpkeep(h, physical = true)
            val own = (h.world.getBlockAt(10, 64, 0).state as org.bukkit.block.Barrel).inventory
            val ack = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); ack }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start(); upkeep.publish()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { upkeep.deliver().join() })?.reason shouldBe "materials-missing"
            receipt shouldBe null
            h.service.upkeepRequirements(upkeep.city) shouldBe mapOf(upkeep.worker.id to mapOf("COBBLESTONE" to 1L))
            h.service.workInputsReady(upkeep.city, upkeep.worker) shouldBe false
            h.service.upkeepSupplyRequests(upkeep.city) { emptyMap() } shouldBe
                listOf(CivSupplyRequest(upkeep.worker.assignment!!, "COBBLESTONE", 16, 0))
            own.setItem(0, ItemStack(Material.COBBLESTONE, 2))
            captureServicePlanningStorage(h.service, upkeep.city)
            h.service.workInputsReady(upkeep.city, upkeep.worker) shouldBe true
            val result = upkeep.deliver()
            val tx = requireNotNull(receipt)
            tx.changes.single() shouldBe CivInventorySlotChange(CivPosition(10, 64, 0), "BARREL", 0,
                CivPlainItem("COBBLESTONE", 2), CivPlainItem("COBBLESTONE", 1))
            tx.after.stock shouldBe Stock()
            own.getItem(0)!!.amount shouldBe 2
            var allowed = true
            every { h.worldAdapter.canModify(any(), any(), any()) } answers { allowed }
            allowed = false
            ack.complete(tx)
            h.tickUntil("native road hut authority conflict") { result.isDone }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason shouldBe "inventory-conflict"
            own.getItem(0)!!.amount shouldBe 2
            h.world.getBlockAt(20, 63, 0).type shouldBe Material.DIRT
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }
})

class CivsRecoveryChunkAdmissionTest : FunSpec({
    test("a quarantined cold road receipt loads its bounded proof footprint without ordinary city admission") {
        mockkConstructor(CivsChunkLoading::class)
        try {
            serviceScenario { h ->
                val upkeep = ServiceRoadUpkeep(h, physical = true)
                val before = upkeep.city
                val after = CivsRoadMaintenance.complete(before, upkeep.work, h.time, nativePaid = true)
                val control = requireNotNull(before.building(upkeep.work.buildingId)?.control)
                val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                    listOf(CivInventorySlotChange(control, "BARREL", 0, CivPlainItem("COBBLESTONE", 1), null)),
                    worldChanges = upkeep.work.changes,
                    upkeep = CivRoadUpkeepReceipt(upkeep.worker, before.stock, upkeep.work, h.time)).validated()
                h.world.getBlockAt(20, 63, 0).type = Material.COBBLESTONE
                h.world.unloadChunk(0, 0)
                h.world.unloadChunk(1, 0)
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(after))
                every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
                every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
                every { h.worldAdapter.validateRoadUpkeep(any(), upkeep.work) } throws CivsRejection("lands-denied")
                var loadRecovery = false
                var demand = emptySet<PaperChunkKey>()
                var ordinaryDemand = emptyMap<UUID, Collection<CivPosition>>()
                val loader = CivsService::class.java.getDeclaredField("chunkLoading").also { it.isAccessible = true }
                    .get(h.service) as CivsChunkLoading
                every { loader.pulse(any(), any(), any()) } answers {
                    ordinaryDemand = thirdArg<Map<UUID, Collection<CivPosition>>>()
                    ordinaryDemand.keys shouldBe firstArg<Collection<Settlement>>().mapTo(hashSetOf()) { it.id }
                    val receipts = secondArg<Collection<Set<PaperChunkKey>>>()
                    demand = receipts.flatten().toSet()
                    if (receipts.isNotEmpty()) {
                        firstArg<Collection<Settlement>>().any { it.id == before.id } shouldBe false
                        if (loadRecovery) demand.forEach { h.world.loadChunk(it.x, it.z) }
                    }
                }
                every { loader.active(any()) } returns false
                h.start()
                h.service.ready() shouldBe true
                h.service.debugState("overview", before.id, null)["inventory_recovery_total"] shouldBe 1
                val journal = CivsService::class.java.getDeclaredField("inventoryJournal").also { it.isAccessible = true }
                    .get(h.service) as CivsInventoryJournal
                journal.pendingNativeChunks().flatten().toSet() shouldBe setOf(PaperChunkKey(h.world.uid, 0, 0),
                    PaperChunkKey(h.world.uid, 1, 0), PaperChunkKey(h.world.uid, 1, -1))
                h.tickUntil("quarantined road receipt chunk demand after startup") { demand.isNotEmpty() }
                verify(atLeast = 1) { loader.pulse(any(), any(), any()) }
                h.world.isChunkLoaded(0, 0) shouldBe false
                h.world.isChunkLoaded(1, 0) shouldBe false
                demand shouldBe setOf(PaperChunkKey(h.world.uid, 0, 0),
                    PaperChunkKey(h.world.uid, 1, 0), PaperChunkKey(h.world.uid, 1, -1))
                h.service.debugState("overview", before.id, null)["inventory_recovery_total"] shouldBe 1
                loadRecovery = true
                h.tickUntil("cold accepted receipt reaches its applied native proof") {
                    h.service.debugState("overview", before.id, null)["inventory_recovery_total"] == 0
                }
                h.tick()
                demand shouldBe emptySet()
                // Quarantine has ended: the ordinary city envelope resumes, with no extra actors or retained routes in this fixture.
                ordinaryDemand shouldBe mapOf(before.id to emptyList<CivPosition>())
                h.service.settlements().single() shouldBe after
                h.saves shouldBe emptyList()
                h.world.getBlockAt(20, 63, 0).type shouldBe Material.COBBLESTONE
                (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Container)
                    .inventory.getItem(0) shouldBe null
                verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
                verify(exactly = 0) { h.worldAdapter.validateRoadUpkeep(any(), upkeep.work) }
            }
        } finally { unmockkConstructor(CivsChunkLoading::class) }
    }

    test("an unfinished leaf receipt loads its original cold authority chunk after the drop crosses a chunk edge") {
        mockkConstructor(CivsChunkLoading::class)
        try {
            serviceScenario(nativeGeometry = true, storageFactory = { plugin -> CivsStorage(plugin) { emptyArray() }.also {
                it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
            } }) { h ->
                val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(23, 63, 20))
                val worker = spatialWorker(mill, "IRON_AXE")
                val before = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), mill),
                    workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
                val control = requireNotNull(mill.control)
                for (x in 1..3) h.world.loadChunk(x, 1)
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
                val holder = (h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Container).inventory
                val leaf = CivPosition(47, 67, 30)
                val ground = CivPosition(48, 64, 30)
                val item = LeafItemFixture(h.paper.server, UUID.randomUUID(), ItemStack(Material.APPLE, 1)).apply {
                    setLocation(Location(h.world, ground.x + .5, ground.y.toDouble(), ground.z + .5))
                    setOnGround(true)
                    pickupDelay = 0
                    h.paper.server.registerEntity(this)
                }
                val proof = CivForestryLeafLootProof(before.id, before.owner, before.worldId, mill.id,
                    listOf(CivForestryLeafSource(item.uniqueId, leaf)))
                CivsForestryLeafLoot.mark(item, proof)
                val transfer = CivCollectedTransfer(worker.id, CivsEquipment.slots(worker),
                    ground = listOf(CivGroundItemChange(item.uniqueId, ground, CivResidentItem("APPLE", 1), null)),
                    containers = listOf(CivNativeContainerChange(control, "BARREL", 0, null, CivResidentItem("APPLE", 1))),
                    forestryLeafLoot = proof)
                val after = before.copy(revision = before.revision + 1)
                val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after, emptyList(),
                    collected = transfer).validated()
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(after))
                every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
                every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
                h.world.unloadChunk(leaf.x shr 4, leaf.z shr 4)
                var loadRecovery = false
                var demand = emptySet<PaperChunkKey>()
                var ordinaryDemand = emptyMap<UUID, Collection<CivPosition>>()
                val loader = CivsService::class.java.getDeclaredField("chunkLoading").also { it.isAccessible = true }
                    .get(h.service) as CivsChunkLoading
                every { loader.pulse(any(), any(), any()) } answers {
                    ordinaryDemand = thirdArg<Map<UUID, Collection<CivPosition>>>()
                    ordinaryDemand.keys shouldBe firstArg<Collection<Settlement>>().mapTo(hashSetOf()) { it.id }
                    demand = secondArg<Collection<Set<PaperChunkKey>>>().flatten().toSet()
                    if (loadRecovery) demand.forEach { h.world.loadChunk(it.x, it.z) }
                }
                every { loader.active(any()) } returns false
                h.start()
                h.service.ready() shouldBe true
                h.service.debugState("overview", before.id, null)["inventory_recovery_total"] shouldBe 1
                val journal = CivsService::class.java.getDeclaredField("inventoryJournal").also { it.isAccessible = true }
                    .get(h.service) as CivsInventoryJournal
                journal.pendingNativeChunks().flatten().toSet() shouldBe setOf(PaperChunkKey(h.world.uid, 1, 1),
                    PaperChunkKey(h.world.uid, 2, 1), PaperChunkKey(h.world.uid, 3, 1))
                h.tickUntil("cold leaf authority chunk demand after startup") { demand.isNotEmpty() }
                verify(atLeast = 1) { loader.pulse(any(), any(), any()) }
                h.world.isChunkLoaded(2, 1) shouldBe false
                demand shouldBe setOf(PaperChunkKey(h.world.uid, 1, 1), PaperChunkKey(h.world.uid, 2, 1),
                    PaperChunkKey(h.world.uid, 3, 1))
                loadRecovery = true
                h.tickUntil("leaf source authority and exact native receipt retirement") {
                    h.service.debugState("overview", before.id, null)["inventory_recovery_total"] == 0
                }
                h.tick()
                demand shouldBe emptySet()
                // Quarantine has ended: the ordinary city envelope resumes, with no extra actors or retained routes in this fixture.
                ordinaryDemand shouldBe mapOf(before.id to emptyList<CivPosition>())
                item.isValid shouldBe false
                holder.getItem(0) shouldBe ItemStack(Material.APPLE, 1)
                h.service.settlements().single() shouldBe after
                h.saves shouldBe emptyList()
                verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
                verify(exactly = 1) { h.store.markConsumedItems(tx) }
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            }
        } finally { unmockkConstructor(CivsChunkLoading::class) }
    }
})

class CivsRoadGradeRecoveryServiceTest : FunSpec({
    for (state in listOf("before", "mixed", "lands-denied", "unrelated-model"))
    test("a committed grade receipt rechecks its before witness and current native authority for $state") {
        serviceScenario { h ->
            val base = roadAdmissionCity().copy(worldId = h.world.uid, owner = h.owner)
            val route = listOf(64, 64, 64, 65, 66, 67, 67, 67, 66, 65, 64, 64, 64)
                .mapIndexed { z, y -> CivPosition(0, y, z) }
            val source = base.buildings.last().copy(type = BuildingType.HOUSE, anchor = CivPosition(0, 63, 40),
                bounds = CivBox(CivPosition(0, 63, 40), CivPosition(5, 72, 45)), entrance = route.last(), transit = null)
            val before = CivsRules.validate(base.copy(buildings = base.buildings.dropLast(1) + source))
            val plan = CivPlacementPlan(source.id, before.id, before.worldId, source, emptyList(), terrain = CivTerraformPlan(
                CivBox(CivPosition(0, 63, 0), CivPosition(0, 68, 12)), emptyList(), route, 63, 0, 0))
            val target = CivsRoadMaintenance.prepare(before, listOf(plan)).targets.single { it.routeOverride != null }
            val worker = before.workers.single()
            val upper = target.floor.copy(y = target.floor.y + 1)
            val work = CivWorldWork(worker.id, worker.completedCycles, requireNotNull(worker.assignment),
                target.floor.copy(x = 1, y = 64), listOf(CivPlanBlock(target.floor, "minecraft:dirt", target.after),
                    CivPlanBlock(upper, "minecraft:dirt", "minecraft:air")), inputs = mapOf("COBBLESTONE" to 1L),
                status = "repairing-road", focus = target.floor, upkeep = target)
            val after = CivsRoadMaintenance.complete(before, work, h.time)
            val receipt = CivRoadUpkeepReceipt(worker, before.stock, work, h.time)
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after, emptyList(),
                worldChanges = work.changes, upkeep = receipt).validated()
            val loaded = if (state == "unrelated-model") after.copy(name = "Different committed model") else after
            for (x in -1..0) for (z in -1..0) h.world.loadChunk(x, z)
            h.world.getBlockAt(target.floor.x, target.floor.y, target.floor.z).blockData =
                Bukkit.createBlockData(if (state == "mixed") target.after else "minecraft:dirt")
            h.world.getBlockAt(upper.x, upper.y, upper.z).type = Material.DIRT
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(loaded))
            every { h.store.loadRetainedPlanSource(any()) } returns CompletableFuture.completedFuture(null)
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
            every { h.worldAdapter.validateRoadUpkeep(any(), work) } answers {
                val witness = firstArg<Settlement>()
                try { target.validated(witness) }
                catch (_: IllegalArgumentException) { throw CivsRejection("stale-worksite") }
                witness shouldBe before
                if (state == "lands-denied") throw CivsRejection("lands-denied")
            }
            h.start()
            val succeeds = state in setOf("before", "mixed")
            h.tickUntil("grade receipt recovery or its retained authority fence") {
                val debug = h.service.debugState("overview", before.id, null)
                if (succeeds) debug["inventory_recovery_total"] == 0 else debug["blocked_recovery_total"] == 1
            }
            h.tick()
            h.service.settlements().single() shouldBe loaded
            h.saves shouldBe emptyList()
            h.service.settlements().single().workers shouldBe after.workers
            h.world.getBlockAt(target.floor.x, target.floor.y, target.floor.z).blockData.asString shouldBe
                if (succeeds) target.after else "minecraft:dirt"
            h.world.getBlockAt(upper.x, upper.y, upper.z).type shouldBe if (succeeds) Material.AIR else Material.DIRT
            verify(exactly = if (succeeds) 1 else 0) { h.store.deleteInventoryTransaction(tx.id) }
            verify(exactly = if (state == "unrelated-model") 0 else 1) { h.worldAdapter.validateRoadUpkeep(any(), work) }
            verify(exactly = 0) { h.citizens.requireWorldWorkArrival(any(), any(), any()) }
        }
    }
})

private class ServiceRoadUpkeep(private val h: ServiceScenario, physical: Boolean = false, cobblestone: Int = 0) {
    private val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT,
        control = CivPosition(10, 64, 0).takeIf { physical })
    private val floor = CivPosition(20, 63, 0)
    private val road = CivBuilding(UUID.randomUUID(), BuildingType.ROAD, 1, floor, 0, "civs/infrastructure/test-road",
        CivBox(floor, floor.copy(y = 65)), floor.copy(y = 64), BuildingPhase.ACTIVE,
        transit = listOf(floor.copy(y = 64)))
    val worker = h.city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1).let { worker ->
        if (cobblestone == 0) worker else CivsEquipment.replace(worker,
            MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
                it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", cobblestone)
            })
    }
    val city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(),
        inventory = CivInventory(CivStorageMode.PHYSICAL).takeIf { physical }, stock = if (physical) Stock() else h.city.stock,
        buildings = listOf(h.city.buildings.first(), hut, road), workers = listOf(worker)))
    val work = CivWorldWork(worker.id, worker.completedCycles, hut.id, floor.copy(x = 19, y = 64),
        listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")),
        inputs = if (physical) mapOf("COBBLESTONE" to 1L) else emptyMap(), status = "repairing-road", focus = floor,
        upkeep = CivRoadUpkeep(city.id, city.worldId, city.owner, road.id, road, floor, "minecraft:cobblestone", null, emptyList()))
    private val plan = CivPlacementPlan(road.id, city.id, city.worldId, road,
        listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")), "a".repeat(64))

    init {
        h.world.loadChunk(1, 0)
        if (physical) h.world.getBlockAt(10, 64, 0).type = Material.BARREL
        h.world.getBlockAt(20, 63, 0).type = Material.DIRT
        h.world.getBlockAt(20, 62, 0).type = Material.STONE
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
        every { h.worldAdapter.captureRoadUpkeep(any(), worker, any(), any()) } answers {
            work.takeIf { h.world.getBlockAt(20, 63, 0).type == Material.DIRT }
        }
        every { h.worldAdapter.roadUpkeepPending(any(), work) } answers { h.world.getBlockAt(20, 63, 0).type == Material.DIRT }
    }

    fun publish() {
        val runtime = CivsService::class.java.getDeclaredField("roadMaintenance").also { it.isAccessible = true }
            .get(h.service) as CivsRoadMaintenance
        runtime.publish(city, CivsRoadMaintenance.prepare(city, listOf(plan)))
    }

    @Suppress("UNCHECKED_CAST")
    fun deliver(): CompletableFuture<Settlement> = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java,
        UUID::class.java, UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
        .also { it.isAccessible = true }.invokeService(h.service, city.id, worker.id, hut.id, worker.completedCycles,
            worker.nextDeliveryAt, null, h.time) as CompletableFuture<Settlement>
}
