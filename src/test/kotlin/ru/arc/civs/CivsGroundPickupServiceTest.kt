package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack

class CivsGroundPickupServiceTest : FunSpec({
    test("RAM admission and commit hold city authority through the exact ground pickup") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val admission = CompletableFuture<CivInventoryTransaction>()
            val commit = CompletableFuture<Settlement>()
            var receipt: CivInventoryTransaction? = null
            var committing = false
            storage.persistCollectedNative = { _, _ -> error("Fresh ground pickup requested native persistence") }
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); admission }
            every { h.store.completeInventoryTransaction(any()) } answers {
                firstArg<CivInventoryTransaction>() shouldBe receipt
                item.isValid shouldBe false
                committing = true
                commit
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            h.tickUntil("ground intent admission") { receipt != null }
            collecting.isDone shouldBe false
            item.isValid shouldBe true
            storage.debug(before.id)["total"] shouldBe 1
            verify(exactly = 0) { h.store.completeInventoryTransaction(any()) }
            h.service.collectionAvailable(before.id) shouldBe false
            val duplicate = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(duplicate) })?.reason shouldBe "city-busy"
            val accepted = requireNotNull(receipt)
            admission.complete(accepted)
            h.tickUntil("ground pickup awaiting RAM commit") { committing }
            item.isValid shouldBe false
            h.service.settlements().single() shouldBe before
            storage.debug(before.id)["total"] shouldBe 1
            collecting.isDone shouldBe false
            accepted.after.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
            commit.complete(accepted.after)
            h.await(collecting) shouldBe accepted.after
            storage.debug(before.id)["total"] shouldBe 0
            val replay = shouldThrow<CompletionException> {
                h.await(h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId)))
            }
            CivsDiagnostics.rejection(replay)?.reason shouldBe "stale-delivery"
            h.service.settlements().single() shouldBe accepted.after
            verify(exactly = 0) { h.store.save(any()) }
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()); h.store.completeInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("a legacy ground receipt retries its own native timeout after five seconds without delaying another town") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val remoteHall = before.buildings.first().copy(id = UUID.randomUUID(), anchor = CivPosition(256, 64, 0),
                bounds = CivBox(CivPosition(254, 63, -2), CivPosition(258, 68, 2)), entrance = CivPosition(256, 64, -2))
            val remote = before.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), name = "Second Town",
                center = remoteHall.anchor, buildings = listOf(remoteHall), workers = listOf(h.city.workers[1]))
            h.world.loadChunk(16, 0)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before, remote))
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val remoteItem = civsGroundFixture(h.paper, h.world, Location(h.world, 257.5, 64.0, .5), ItemStack(Material.COAL, 3))
            val receipt = readLegacyGroundPickup(before, item)
            val remoteReceipt = readLegacyGroundPickup(remote, remoteItem)
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt, remoteReceipt))
            val afterAttempts = mutableListOf<UUID>()
            storage.persistCollectedNative = { tx, beforeJournal ->
                beforeJournal shouldBe false
                afterAttempts += tx.id
                if (tx.id == receipt.id && afterAttempts.count { it == receipt.id } == 1)
                    CompletableFuture.failedFuture(CivsNativePersistenceRetry("Native persistence timed out"))
                else CompletableFuture.completedFuture(Unit)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val start = h.time
            h.start()
            h.tickUntil("other city's receipt commits while the first native save is backing off") { h.saves.isNotEmpty() }
            afterAttempts shouldBe listOf(receipt.id, remoteReceipt.id)
            h.time shouldBe start
            item.isValid shouldBe false
            remoteItem.isValid shouldBe false
            h.saves.single().first shouldBe remoteReceipt.after
            storage.debug(before.id)["total"] shouldBe 1
            verify(exactly = 0) { h.store.markConsumedItems(match { it.id == receipt.id }) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(receipt.id) }
            h.commit()
            h.service.settlements().single { it.id == remote.id }.workers.single().items!![6] shouldBe CivResidentItem("COAL", 3)
            h.time += 4_999
            h.tick()
            afterAttempts shouldBe listOf(receipt.id, remoteReceipt.id)
            h.time++
            h.tickUntil("retry native ACK and one recovered city credit") { h.saves.size == 2 }
            afterAttempts shouldBe listOf(receipt.id, remoteReceipt.id, receipt.id)
            val saved = h.saves.last()
            saved.first.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
            saved.second.complete(saved.first)
            h.tickUntil("recovered receipt retirement") { storage.debug(before.id)["total"] == 0 }
            h.service.city(h.player)!!.revision shouldBe before.revision + 1
            h.time += 10_000
            h.tick()
            afterAttempts.size shouldBe 3
            h.saves.size shouldBe 2
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 2) { h.store.markConsumedItems(any()) }
            verify(exactly = 2) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("a failed RAM consumed marker keeps the exact ground lock and prevents bag publication") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also {
                storage = it; it.persistCollectedNative = { _, _ -> error("Fresh ground pickup requested native persistence") }
            }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val marker = CompletableFuture<CivConsumedItemIndex>()
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.completeInventoryTransaction(any()) } answers {
                CompletableFuture.completedFuture(firstArg<CivInventoryTransaction>().after)
            }
            every { h.store.markConsumedItems(any()) } returns marker
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            h.tickUntil("native debit while RAM marker is pending") { !item.isValid }
            item.isValid shouldBe false
            h.service.settlements().single() shouldBe before
            collecting.isDone shouldBe false
            marker.completeExceptionally(IllegalStateException("Consumed UUID staging failed"))
            shouldThrow<CompletionException> { h.await(collecting) }
            h.service.settlements().single() shouldBe before
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.markConsumedItems(any()) }
            storage.debug(before.id)["total"] shouldBe 1
            h.time += 10_000
            h.tick()
            h.service.settlements().single() shouldBe before
            verify(exactly = 0) { h.store.save(any()) }
            verify(exactly = 1) { h.store.completeInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.markConsumedItems(any()) }
        }
    }

    for (legacy in listOf(false, true)) test("city revision drift rejects ground credit during admission or legacy native ACK legacy=$legacy") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val nativeAck = CompletableFuture<Unit>()
            val admission = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            var writingNative = false
            if (legacy) {
                val old = readLegacyGroundPickup(before, item)
                every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(old))
                storage.persistCollectedNative = { tx, beforeJournal ->
                    tx shouldBe old
                    beforeJournal shouldBe false
                    writingNative = true
                    nativeAck
                }
            } else {
                storage.persistCollectedNative = { _, _ -> error("Fresh ground pickup requested native persistence") }
                every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); admission }
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = if (legacy) null else h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            h.tickUntil("ground operation reaches held acknowledgement") { writingNative || receipt != null }
            item.isValid shouldBe !legacy
            val advanced = before.copy(revision = before.revision + 1)
            h.replaceCityInMemory(advanced)
            if (legacy) {
                nativeAck.complete(Unit)
                h.tickUntil("drift blocks legacy recovery") {
                    h.service.debugState("overview", before.id, null)["blocked_recovery_total"] == 1
                }
            } else {
                admission.complete(requireNotNull(receipt))
                CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(requireNotNull(collecting)) })
                    ?.reason shouldBe "stale-delivery"
            }
            h.saves shouldBe emptyList()
            h.service.settlements().single() shouldBe advanced
            item.isValid shouldBe !legacy
            storage.debug(before.id)["total"] shouldBe if (legacy) 1 else 0
            verify(exactly = if (legacy) 0 else 1) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.completeInventoryTransaction(any()) }
        }
    }

    for (autonomous in listOf(false, true)) test("ground collection fences both native states before committing and retiring autonomous=$autonomous") {
        lateinit var storage: CivsStorage
        val order = mutableListOf<String>()
        val events = mutableListOf<Map<String, Any?>>()
        mockkObject(CivsDiagnostics)
        try {
            every { CivsDiagnostics.event(any(), *anyVararg()) } answers {
                if (firstArg<String>() == "resident_ground_collected") events += secondArg<Array<Pair<String, Any?>>>().toMap()
                Unit
            }
            serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
                CivsStorage(plugin) { emptyArray() }.also { storage = it }
            }) { h ->
                val worker = h.city.workers.first()
                val before = h.city.copy(workers = listOf(worker), stock = if (autonomous) h.city.stock else Stock(),
                    inventory = if (autonomous) null else CivInventory(CivStorageMode.VIRTUAL),
                    autonomy = if (autonomous) CivAutonomy(paused = true) else null)
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
                every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
                val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
                storage.persistCollectedNative = { _, _ -> error("Fresh ground pickup requested native persistence") }
                var receipt: CivInventoryTransaction? = null
                val journal = CompletableFuture<CivInventoryTransaction>()
                val committedModel = CompletableFuture<Settlement>()
                val retirement = CompletableFuture<Boolean>()
                every { h.store.saveInventoryTransaction(any()) } answers { order += "admission"; receipt = firstArg(); journal }
                every { h.store.completeInventoryTransaction(any()) } answers {
                    firstArg<CivInventoryTransaction>() shouldBe receipt
                    item.isValid shouldBe false
                    order += "commit"
                    committedModel
                }
                every { h.store.markConsumedItems(any()) } answers {
                    order += "marker"
                    CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY.adding(listOf(item.uniqueId)))
                }
                every { h.store.deleteInventoryTransaction(any()) } answers { order += "retire"; retirement }
                h.start()
                val collected = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
                h.tickUntil("ground intent admission") { receipt != null }
                order shouldBe listOf("admission")
                item.isValid shouldBe true
                events shouldBe emptyList()
                val admitted = requireNotNull(receipt)
                journal.complete(admitted)
                h.tickUntil("ground RAM commit and marker submission") { order.last() == "marker" }
                order shouldBe listOf("admission", "commit", "marker")
                item.isValid shouldBe false
                h.service.settlements().single().workers.single().hasCollectedItems shouldBe null
                committedModel.complete(admitted.after)
                h.tickUntil("receipt retirement") { order.last() == "retire" }
                order.last() shouldBe "retire"
                events shouldBe emptyList()
                collected.isDone shouldBe false
                h.service.collectionAvailable(before.id) shouldBe false
                retirement.complete(true)
                val committed = h.await(collected)
                committed.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
                committed.workers.single().hasCollectedItems shouldBe true
                committed.stock shouldBe before.stock
                committed.inventory shouldBe before.inventory
                events.single()["worker"] shouldBe worker.id
                events.single()["transaction"] shouldBe admitted.id
                events.single()["items"] shouldBe 8
                events.single()["slots"] shouldBe 1
                h.service.collectionAvailable(before.id) shouldBe true
                verify(exactly = 0) { h.store.save(any()) }
            }
        } finally { unmockkObject(CivsDiagnostics) }
    }

    for (legacy in listOf(false, true)) test("failed ground admission or legacy native ACK never credits the resident legacy=$legacy") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            var nativeAttempts = 0
            if (legacy) {
                val old = readLegacyGroundPickup(before, item)
                every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(old))
                storage.persistCollectedNative = { tx, beforeJournal ->
                    tx shouldBe old
                    beforeJournal shouldBe false
                    nativeAttempts++
                    CompletableFuture.failedFuture(IllegalStateException("Legacy native flush failed"))
                }
            } else {
                storage.persistCollectedNative = { _, _ -> error("Fresh ground pickup requested native persistence") }
                every { h.store.saveInventoryTransaction(any()) } returns
                    CompletableFuture.failedFuture(CivsRejection("ground-history-full"))
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            if (legacy) h.tickUntil("failed legacy native flush") {
                h.service.debugState("overview", before.id, null)["blocked_recovery_total"] == 1
            } else {
                val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
                CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(collecting) })
                    ?.reason shouldBe "ground-history-full"
            }
            h.saves shouldBe emptyList()
            h.service.city(h.player)!!.workers.single() shouldBe worker
            item.isValid shouldBe !legacy
            verify(exactly = if (legacy) 0 else 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.completeInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
            storage.debug(before.id)["total"] shouldBe if (legacy) 1 else 0
            h.service.corpseSettlements().size shouldBe if (legacy) 0 else 1
            h.time += 10_000
            h.tick()
            nativeAttempts shouldBe if (legacy) 1 else 0
            h.saves shouldBe emptyList()
        }
    }

    for (stale in listOf("missing", "left-reach")) test("fresh ground $stale while journal writes cancels without credit") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it; it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) } }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            var feet = Location(h.world, 2.5, 64.0, .5)
            every { h.citizens.residentCollectionFeet(worker.id, any()) } answers { feet }
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            h.paper.performTicks(1)
            if (stale == "missing") item.remove() else feet = Location(h.world, 12.5, 64.0, .5)
            journal.complete(receipt!!)
            val failed = shouldThrow<CompletionException> { h.await(collecting) }
            CivsDiagnostics.rejection(failed)?.reason shouldBe if (stale == "missing") "stale-delivery" else "worker-too-far"
            h.saves shouldBe emptyList()
            h.service.city(h.player)!!.workers.single() shouldBe worker
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt!!.id) }
            if (stale != "missing") item.itemStack.amount shouldBe 8
            h.service.corpseSettlements().size shouldBe 1
        }
    }

    for ((nativeAfter, markedBefore) in listOf(false to false, true to false, true to true))
        test("ground journal recovery completes one credit without a live actor nativeAfter=$nativeAfter marked=$markedBefore") {
        lateinit var storage: CivsStorage
        var fences = 0
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it; it.persistCollectedNative = { _, _ -> fences++; CompletableFuture.completedFuture(Unit) } }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val afterWorker = worker.copy(items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("COAL", 8) else null },
                hasCollectedItems = true)
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision,
                before.copy(workers = listOf(afterWorker), revision = before.revision + 1), emptyList(),
                collected = CivCollectedTransfer(worker.id, CivsEquipment.slots(worker), ground = listOf(
                    CivGroundItemChange(item.uniqueId, CivPosition(3, 64, 0), CivResidentItem("COAL", 8), null))))
            if (nativeAfter) item.remove()
            if (markedBefore) every { h.store.loadConsumedItems() } returns CompletableFuture.completedFuture(
                CivConsumedItemIndex.EMPTY.adding(listOf(item.uniqueId)))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            every { h.citizens.residentCollectionFeet(any(), any()) } returns null
            h.start()
            h.tickUntil("recovered city save") { h.saves.isNotEmpty() }
            val saved = h.saves.single()
            saved.first shouldBe receipt.after
            fences shouldBe 1 // The admitted source fence was acknowledged before this persisted journal.
            item.isValid shouldBe false
            saved.second.complete(saved.first)
            repeat(4) { h.paper.performTicks(1) }
            h.service.settlements().single() shouldBe receipt.after
            h.service.corpseSettlements().size shouldBe 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
            verify(exactly = 0) { h.citizens.residentCollectionFeet(any(), any()) }
        }
    }

    test("a committed ground receipt retries a native timeout then retires without another city credit") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val afterWorker = worker.copy(items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("COAL", 8) else null },
                hasCollectedItems = true)
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision,
                before.copy(workers = listOf(afterWorker), revision = before.revision + 1), emptyList(),
                collected = CivCollectedTransfer(worker.id, CivsEquipment.slots(worker), ground = listOf(
                    CivGroundItemChange(item.uniqueId, CivPosition(3, 64, 0), CivResidentItem("COAL", 8), null))))
            item.remove()
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(receipt.after))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            val retired = mutableListOf<UUID>()
            every { h.store.deleteInventoryTransaction(any()) } answers {
                retired += firstArg<UUID>(); CompletableFuture.completedFuture(true)
            }
            var attempts = 0
            storage.persistCollectedNative = { _, beforeJournal ->
                beforeJournal shouldBe false
                if (++attempts == 1) CompletableFuture.failedFuture(CivsNativePersistenceRetry("Native persistence timed out"))
                else CompletableFuture.completedFuture(Unit)
            }
            h.start()
            h.tickUntil("first committed receipt persistence attempt") { attempts == 1 }
            h.tick()
            retired shouldBe emptyList()
            h.saves shouldBe emptyList()
            h.time += 4_999
            h.tick()
            attempts shouldBe 1
            h.time++
            h.tickUntil("committed receipt recovery retirement") { retired.isNotEmpty() }
            attempts shouldBe 2
            retired shouldBe listOf(receipt.id)
            h.saves shouldBe emptyList()
            h.service.city(h.player) shouldBe receipt.after
            verify(exactly = 1) { h.store.markConsumedItems(receipt) }
        }
    }
})

class CivsForestryLeafLootServiceTest : FunSpec({
    test("accepted native leaf events journal ground to the forester workplace without an actor or stock credit") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin -> CivsStorage(plugin) { emptyArray() }.also {
            storage = it
            it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
        } }) { h ->
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(20, 63, 20))
            val worker = spatialWorker(mill, "IRON_AXE")
            val before = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), mill), workers = listOf(worker),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            every { h.citizens.residentCollectionFeet(any(), any()) } throws IllegalStateException("Leaf collection must not invent resident arrival")
            val control = requireNotNull(mill.control)
            for (x in -1..3) for (z in -1..3) h.world.loadChunk(x, z)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val holder = h.world.getBlockAt(control.x, control.y, control.z).state as org.bukkit.block.Barrel
            h.start()
            val leaf = CivPosition(30, 67, 30)
            val block = h.world.getBlockAt(leaf.x, leaf.y, leaf.z)
            block.blockData = Bukkit.createBlockData("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]")
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.LeavesDecayEvent(block))
            val item = LeafItemFixture(h.paper.server, UUID.randomUUID(), ItemStack(Material.APPLE, 2)).apply {
                setLocation(Location(h.world, leaf.x + .5, leaf.y + .5, leaf.z + .5))
                setOnGround(true)
                pickupDelay = 0
                h.paper.server.registerEntity(this)
            }
            h.paper.server.pluginManager.callEvent(org.bukkit.event.entity.ItemSpawnEvent(item))
            requireNotNull(CivsForestryLeafLoot.proof(item))
            block.type = Material.AIR
            item.setLocation(Location(h.world, leaf.x + .5, 64.0, leaf.z + .5))
            h.tickUntil("exact native leaf transfer") { holder.inventory.getItem(0)?.amount == 2 }
            h.tickUntil("leaf receipt retirement") { h.service.settlements().single().revision == before.revision + 1 &&
                storage.debug(before.id)["total"] == 0 }
            item.isValid shouldBe false
            holder.inventory.getItem(0) shouldBe ItemStack(Material.APPLE, 2)
            h.service.settlements().single().workers shouldBe before.workers
            h.service.settlements().single().stock shouldBe before.stock
            verify(exactly = 1) { h.store.saveInventoryTransaction(match { it.collected?.forestryLeafLoot != null }) }
            verify(exactly = 1) { h.store.markConsumedItems(match { it.collected?.ground?.single()?.entityId == item.uniqueId }) }
            storage.debug(before.id)["total"] shouldBe 0
        }
    }
})

private fun readLegacyGroundPickup(before: Settlement, item: Item): CivInventoryTransaction {
    val worker = before.workers.single()
    val stack = requireNotNull(CivResidentItem.of(item.itemStack))
    val credited = worker.copy(items = List(CivsEquipment.SLOTS) { if (it == 6) stack else null }, hasCollectedItems = true)
    val at = item.location
    val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision,
        before.copy(workers = listOf(credited), revision = before.revision + 1), emptyList(),
        collected = CivCollectedTransfer(worker.id, CivsEquipment.slots(worker), ground = listOf(
            CivGroundItemChange(item.uniqueId, CivPosition(at.blockX, at.blockY, at.blockZ), stack, null))))
    val directory = java.nio.file.Files.createTempDirectory("legacy-ground-receipt-")
    try {
        writeLegacyInventoryReceipt(directory, receipt)
        return CivsStore(directory, Executor(Runnable::run)).use { it.loadInventoryTransactions().join().single() }
    } finally { directory.toFile().deleteRecursively() }
}
