package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class CivsResidentLifecycleServiceTest : FunSpec({
    test("an active tavern serves existing bread without staff and falls back to a stocked hall when empty") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val tavern = h.city.buildings.last().copy(type = BuildingType.TAVERN, control = CivPosition(10, 63, 0))
            val innkeeper = h.city.workers.first().copy(assignment = tavern.id, nextDeliveryAt = 1)
            val guest = h.city.workers[1].copy(assignment = hall.id, nextDeliveryAt = 1, hunger = 10)
            val ready = CivsRules.validate(h.city.copy(buildings = listOf(hall, tavern),
                workers = listOf(innkeeper, guest), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL,
                    buildingMaterials = mapOf(hall.id.toString() to mapOf("BREAD" to 8L),
                        tavern.id.toString() to mapOf("BREAD" to 4L)))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.service.residentSupplySource(ready, guest) shouldBe tavern.id
            h.service.residentSupplySource(ready.copy(workers = listOf(guest)), guest) shouldBe tavern.id
            h.service.residentSupplySource(ready.copy(workers = listOf(guest), inventory = ready.inventory!!.copy(
                buildingMaterials = mapOf(hall.id.toString() to mapOf("BREAD" to 8L)))), guest) shouldBe hall.id
            val pickup = h.service.prepareResidentSupplies(ready.id, guest.id, tavern.id)
            h.tick(); h.commit()
            val after = pickup.join()
            after.inventory!!.buildingStock(tavern.id) shouldBe emptyMap()
            after.inventory!!.buildingStock(hall.id) shouldBe mapOf("BREAD" to 8L)
            CivsEquipment.plainCount(after.workers.first { it.id == guest.id }, "BREAD") shouldBe 4L
            after.workers.first { it.id == innkeeper.id } shouldBe innkeeper
        }
    }

    for (autonomous in listOf(false, true)) test("scroll resurrection is one exact-item RAM operation autonomous=$autonomous") {
        serviceScenario(nativeGeometry = true, storageFactory = {
            CivsStorage(it, ru.arc.paper.playerstate.PaperPlayerDataPersistence { error("Fresh resurrection requested a player save") }) { emptyArray() }
        }) { h ->
            val player = h.paper.addPlayer("ResurrectionOwner").apply {
                addAttachment(h.plugin, "arccivs.use", true)
                addAttachment(h.plugin, "arccivs.admin", autonomous)
                teleport(Location(h.world, .5, 64.0, 2.5))
            }
            val original = h.city.workers.first().copy(skills = mapOf("QUARRY" to 20L),
                items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("BREAD", 2) else null })
            val corpse = CivsCorpses.create(original, CivDeathContext(CivPosition(0, 64, 0), h.time - 1_000))
            val before = h.city.copy(owner = player.uniqueId, stock = if (autonomous) h.city.stock else Stock(),
                inventory = if (autonomous) null else CivInventory(CivStorageMode.VIRTUAL),
                autonomy = if (autonomous) CivAutonomy() else null,
                workers = h.city.workers.drop(1), corpses = listOf(corpse))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            val journal = CompletableFuture<CivInventoryTransaction>()
            val retirement = CompletableFuture<Boolean>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns retirement
            h.service.corpseLocation = { _, _, _ -> Location(h.world, .5, 64.0, .5) }
            val scroll = CivsResurrectionScroll.create(CivsText.fromYaml(civsLocaleSource()), player).apply { amount = 2 }
            player.inventory.setItem(0, scroll)
            h.start()
            val resurrecting = h.service.resurrect(player, before.id, original.id, corpse.diedAt)
            player.inventory.getItem(0) shouldBe scroll
            h.saves.size shouldBe 0
            h.service.settlements().single().corpses shouldBe listOf(corpse)
            val duplicate = h.service.resurrect(player, before.id, original.id, corpse.diedAt)
            (shouldThrow<CompletionException> { duplicate.join() }.cause as CivsRejection).reason shouldBe "city-busy"
            journal.complete(requireNotNull(receipt)); h.tick()
            player.inventory.getItem(0) shouldBe scroll.clone().apply { amount = 1 }
            h.service.settlements().single().corpses shouldBe listOf(corpse)
            h.saves.single().first.workers.last() shouldBe CivsRules.resurrectedWorker(before, corpse)
            h.commit()
            resurrecting.isDone shouldBe false
            verify(exactly = 1) { h.citizens.requestAdmissionAt(before.id, original.id, any()) }
            h.paper.performTicks(25)
            verify(exactly = 1) { h.citizens.requestAdmissionAt(before.id, original.id, any()) }
            retirement.complete(true); h.await(resurrecting)
            val after = h.service.settlements().single()
            after.workers.count { it.id == original.id } shouldBe 1
            after.corpses shouldBe emptyList()
            after.inventory shouldBe before.inventory
            after.stock shouldBe before.stock
            (shouldThrow<CompletionException> { h.service.resurrect(player, before.id, original.id, corpse.diedAt).join() }
                .cause as CivsRejection).reason shouldBe "worker-not-found"
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
            verify(exactly = 2) { h.citizens.requestAdmissionAt(before.id, original.id, any()) }
        }
    }

    for (guard in listOf("expired", "unsafe", "moved")) test("resurrection rechecks $guard after journal IO before consuming the scroll") {
        serviceScenario(nativeGeometry = true, storageFactory = {
            CivsStorage(it, ru.arc.paper.playerstate.PaperPlayerDataPersistence { }) { emptyArray() }
        }) { h ->
            val player = h.paper.addPlayer("RecheckedOwner").apply {
                addAttachment(h.plugin, "arccivs.use", true)
                teleport(Location(h.world, .5, 64.0, 2.5))
            }
            val worker = h.city.workers.first()
            val corpse = CivsCorpses.create(worker, CivDeathContext(CivPosition(0, 64, 0), h.time - 1_000))
            val before = h.city.copy(owner = player.uniqueId, stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                workers = h.city.workers.drop(1), corpses = listOf(corpse))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.service.corpseLocation = { _, _, _ -> Location(h.world, .5, 64.0, .5) }
            val scroll = CivsResurrectionScroll.create(CivsText.fromYaml(civsLocaleSource()), player)
            player.inventory.setItem(0, scroll)
            h.start()
            val resurrecting = h.service.resurrect(player, before.id, worker.id, corpse.diedAt)
            when (guard) {
                "expired" -> h.time = corpse.expiresAt
                "unsafe" -> h.world.getBlockAt(0, 64, 0).type = Material.STONE
                "moved" -> h.service.corpseLocation = { _, _, _ -> Location(h.world, 1.5, 64.0, .5) }
            }
            journal.complete(requireNotNull(receipt)); h.tick()
            resurrecting.isCompletedExceptionally shouldBe true
            player.inventory.getItem(0) shouldBe scroll
            verify(exactly = 0) { h.store.save(any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(any()) }
            verify(exactly = 0) { h.citizens.requestAdmissionAt(any(), any(), any()) }
        }
    }

    for (nativeApplied in listOf(false, true)) test("resurrection recovery classifies the exact scroll before finishing nativeApplied=$nativeApplied") {
        serviceScenario(nativeGeometry = true, storageFactory = {
            CivsStorage(it, ru.arc.paper.playerstate.PaperPlayerDataPersistence { }) { emptyArray() }
        }) { h ->
            val player = h.paper.addPlayer("RecoveredOwner").apply { addAttachment(h.plugin, "arccivs.use", true) }
            val worker = h.city.workers.first()
            val corpse = CivsCorpses.create(worker, CivDeathContext(CivPosition(0, 64, 0), h.time - 1_000))
            val before = h.city.copy(owner = player.uniqueId, stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                workers = h.city.workers.drop(1), corpses = listOf(corpse))
            val scroll = CivsResurrectionScroll.create(CivsText.fromYaml(civsLocaleSource()), player).apply { amount = 2 }
            val nativeBefore = requireNotNull(CivResidentItem.of(scroll))
            val debit = CivResurrectionDebit(player.uniqueId, corpse, CivNativePlayerSlot(0, nativeBefore,
                nativeBefore.copy(amount = 1)), h.time, corpse.position, 64.0)
            val after = CivsRules.resurrect(before, player.uniqueId, worker.id, corpse.diedAt, h.time)
            val receipt = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after, emptyList(), resurrection = debit)
            val admitted = mutableListOf<Boolean>()
            every { h.citizens.requestAdmissionAt(before.id, worker.id, any()) } answers {
                admitted += h.service.corpseSettlements().any { city -> city.id == before.id && city.workers.any { it.id == worker.id } }
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns CompletableFuture.completedFuture(true)
            player.inventory.setItem(0, scroll.clone().apply { amount = if (nativeApplied) 1 else 2 })
            if (nativeApplied) { h.time = corpse.expiresAt; h.world.getBlockAt(0, 64, 0).type = Material.STONE }
            h.start(); h.paper.performTicks(25)
            h.saves.single().first shouldBe after
            player.inventory.getItem(0) shouldBe scroll.clone().apply { amount = 1 }
            h.commit()
            h.service.settlements().single() shouldBe after
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
            verify(exactly = 2) { h.citizens.requestAdmissionAt(before.id, worker.id, any()) }
            admitted shouldBe listOf(false, true)
        }
    }

    for (stillPresent in listOf(true, false)) test("resident minute queues behind work once and rechecks presence=$stillPresent before saving") {
        serviceScenario { h ->
            val hall = h.city.buildings.first()
            val worker = h.city.workers.first().copy(assignment = hall.id, nextDeliveryAt = 1,
                sex = CivSex.MALE, homeId = hall.id, hunger = 20)
            val ready = h.city.copy(workers = listOf(worker), immigration = CivImmigration(4, Long.MAX_VALUE))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            every { h.citizens.workingResidentIds(ready.id) } returns emptySet()
            every { h.citizens.awakeResidentIds(ready.id) } returns setOf(worker.id)
            val viewer = h.paper.addPlayer("ResidentObserver")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            h.start(); h.paper.performTicks(20)
            val injury = h.service.hurtResident(ready.id, worker.id, 1.0)
            h.time += 60_001
            h.paper.performTicks(100)
            h.saves.size shouldBe 1 // One queued minute, despite five pulses while the save is blocked.
            if (!stillPresent) viewer.disconnect()
            h.commit(); injury.join(); h.paper.performTicks(20)
            h.saves.size shouldBe if (stillPresent) 2 else 1
            if (stillPresent) {
                h.saves.last().first.workers.single().hunger shouldBe 19
                h.commit(); h.paper.performTicks(100)
                h.saves.size shouldBe 2
            }
        }
    }

    test("resident injury waits for another city save and retains both updates before publishing death") {
        serviceScenario { h ->
            h.start()
            val worker = h.city.workers.last()
            val member = h.paper.addPlayer("ResidentMember")
            val assigning = h.service.addMember(h.player, member.name)
            h.tick()
            val injury = h.service.hurtResident(h.city.id, worker.id, 20.0)
            injury.isDone shouldBe false
            h.saves.size shouldBe 1
            h.service.city(h.player)!!.workers.size shouldBe 4
            h.commit(); assigning.join()
            h.tick()
            h.saves.size shouldBe 2
            h.service.city(h.player)!!.workers.size shouldBe 4
            h.saves.last().first.workers.size shouldBe 3
            (member.uniqueId in h.saves.last().first.members) shouldBe true
            h.saves.last().first.revision shouldBe h.saves.first().first.revision + 1
            h.commit(); injury.join()
            h.service.city(h.player)!!.workers.none { it.id == worker.id } shouldBe true
        }
    }

    test("unknown resident injury commit blocks later healing and item debits") {
        serviceScenario { h ->
            h.start()
            val worker = h.city.workers.last().id
            val injury = h.service.hurtResident(h.city.id, worker, 3.0)
            h.saves.last().second.completeExceptionally(IllegalStateException("unknown commit")); h.tick()
            injury.isCompletedExceptionally shouldBe true
            h.service.feedWorker(h.player, worker).isCompletedExceptionally shouldBe true
            h.service.supplyWorker(h.player, worker, "BREAD").isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
            CivsResidents.health(h.service.city(h.player)!!.workers.last()) shouldBe 20.0
        }
    }

    test("idle residents are assigned only near a viewer and only after the assignment save is acknowledged") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("ResidentOwner")
            player.teleport(Location(h.world, 1_000.0, 65.0, 0.0))
            val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE,
                control = CivPosition(10, 63, 0))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val city = h.city.copy(owner = player.uniqueId, memberNames = mapOf(player.uniqueId.toString() to player.name),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), hut))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            h.saves shouldBe emptyList()
            player.teleport(Location(h.world, 10.0, 65.0, 0.0)); h.paper.performTicks(20)
            h.saves.single().first.workers.count { it.assignment == hut.id } shouldBe 1
            h.service.city(player)!!.workers.all { it.assignment == null } shouldBe true
            h.commit(); h.paper.performTicks(40)
            h.service.city(player)!!.workers.count { it.assignment == hut.id } shouldBe 1
            h.saves.size shouldBe 1
        }
    }
})

class CivsQueuedTavernPickupServiceTest : FunSpec({
    for (moveAway in listOf(false, true))
    test("a tavern food pickup waits for the city save and rechecks actual feet moved=$moveAway") {
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage ->
                // Native region flush is covered separately; keep this service queue test deterministic.
                storage.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
            }
        }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val tavern = h.city.buildings.last().copy(type = BuildingType.TAVERN, control = CivPosition(10, 63, 0))
            val keeper = h.city.workers.first().copy(assignment = tavern.id, nextDeliveryAt = 1,
                inventory = emptyMap(), items = List(CivsEquipment.SLOTS) { null }, hunger = 20)
            val guest = h.city.workers[1].copy(assignment = hall.id, nextDeliveryAt = 1, hunger = 10)
            val city = CivsRules.validate(h.city.copy(buildings = listOf(hall, tavern), workers = listOf(keeper, guest),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val contents = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Container).inventory
            contents.setItem(0, ItemStack(Material.BREAD, 4))
            var feet = Location(h.world, 9.5, 64.0, .5)
            every { h.citizens.residentCollectionFeet(keeper.id, false) } answers { feet }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val assignment = h.service.assign(h.player, guest.id, null)
            h.saves.size shouldBe 1
            assignment.isDone shouldBe false
            val source = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(9, 64, 0),
                servingFor = guest.id, servingTicket = UUID.randomUUID())
            val pickup = h.service.pickupResidentFood(city.id, keeper.id, source)
            pickup.isDone shouldBe false
            repeat(4) { h.time += 1_000; h.tick() }
            pickup.isDone shouldBe false
            contents.getItem(0)!!.amount shouldBe 4
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            if (moveAway) feet = Location(h.world, 50.5, 64.0, .5)
            h.commit()
            if (moveAway) {
                h.tickUntil("moved keeper rejection") { pickup.isDone }
                pickup.isCompletedExceptionally shouldBe true
                contents.getItem(0)!!.amount shouldBe 4
                h.saves.size shouldBe 1
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            } else {
                h.tickUntil("queued food city save") { h.saves.size == 2 || pickup.isDone }
                if (pickup.isCompletedExceptionally) pickup.join()
                h.saves.size shouldBe 2
                h.commit()
                h.await(pickup)
                val savedKeeper = h.service.settlements().single().workers.first { it.id == keeper.id }
                val carried = CivsEquipment.plainCount(savedKeeper, "BREAD")
                (carried > 0) shouldBe true
                (contents.contents.filterNotNull().sumOf { it.amount }.toLong() + carried) shouldBe 4L
                verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            }
        }
    }

    for (retireAt in listOf("city-wait", "ram-admission"))
    test("a retired queued serving ticket cannot debit its replacement's food retiredAt=$retireAt") {
        val admission = CompletableFuture<CivInventoryTransaction>()
        var firstReceipt: CivInventoryTransaction? = null
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage ->
                storage.persistCollectedNative = { _, _ -> error("Fresh food pickup requested native persistence") }
            }
        }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val tavern = h.city.buildings.last().copy(type = BuildingType.TAVERN, control = CivPosition(10, 63, 0))
            val keeper = h.city.workers.first().copy(assignment = tavern.id, nextDeliveryAt = 1,
                inventory = emptyMap(), items = List(CivsEquipment.SLOTS) { null }, hunger = 20)
            val guest = h.city.workers[1].copy(assignment = hall.id, nextDeliveryAt = 1, hunger = 10)
            val city = CivsRules.validate(h.city.copy(buildings = listOf(hall, tavern), workers = listOf(keeper, guest),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val contents = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Container).inventory
            contents.setItem(0, ItemStack(Material.BREAD, 8))
            val first = UUID.randomUUID()
            val replacement = UUID.randomUUID()
            var activeTicket = first
            every { h.citizens.residentCollectionFeet(keeper.id, false) } returns Location(h.world, 9.5, 64.0, .5)
            every { h.citizens.validateTavernPickup(city.id, keeper.id, guest.id, tavern.id, "BREAD", any()) } answers {
                if (arg<UUID?>(5) != activeTicket) throw CivsRejection("stale-meal")
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } answers {
                val tx = firstArg<CivInventoryTransaction>()
                if (retireAt == "ram-admission" && tx.collected?.food?.servingTicket == first) {
                    firstReceipt = tx
                    admission
                } else CompletableFuture.completedFuture(tx)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.service.assign(h.player, guest.id, null)
            h.saves.size shouldBe 1
            val source = CivResidentFoodSource(tavern.id, requireNotNull(tavern.control), CivPosition(9, 64, 0),
                servingFor = guest.id, servingTicket = first)
            val retired = h.service.pickupResidentFood(city.id, keeper.id, source)
            retired.isDone shouldBe false
            if (retireAt == "ram-admission") {
                h.commit()
                h.tickUntil("first ticket reaches RAM admission") { firstReceipt != null }
                retired.isDone shouldBe false
                // A prepared collection retains the existing five-second world collection cadence even when retired.
                h.time += 5_000
            }
            activeTicket = replacement
            val current = h.service.pickupResidentFood(city.id, keeper.id, source.copy(servingTicket = replacement))
            current.isDone shouldBe false
            contents.getItem(0)!!.amount shouldBe 8
            verify(exactly = if (retireAt == "ram-admission") 1 else 0) { h.store.saveInventoryTransaction(any()) }
            if (retireAt == "city-wait") h.commit()
            if (retireAt == "ram-admission") admission.complete(requireNotNull(firstReceipt))
            h.tickUntil("retired ticket rejection and replacement receipt") { retired.isDone && h.saves.size == 2 }
            shouldThrow<CompletionException> { retired.join() }.cause.let { (it as CivsRejection).reason } shouldBe "stale-meal"
            h.commit()
            h.await(current)
            repeat(8) { h.tick() }
            val savedKeeper = h.service.settlements().single().workers.first { it.id == keeper.id }
            CivsEquipment.plainCount(savedKeeper, "BREAD") shouldBe 4L
            contents.getItem(0)!!.amount shouldBe 4
            verify(exactly = 1) { h.store.saveInventoryTransaction(match { it.collected?.food?.servingTicket == replacement }) }
            verify(exactly = if (retireAt == "ram-admission") 2 else 1) { h.store.saveInventoryTransaction(any()) }
        }
    }
})

class CivsResidentRegenerationServiceTest : FunSpec({
    for (automatic in listOf(false, true))
    test("Auto $automatic resident healing waits for durable save and a delayed save accrues no extra heals") {
        serviceScenario { h ->
            val worker = h.city.workers.first().copy(assignment = h.city.buildings.first().id,
                health = 8.0, hunger = 18, nextDeliveryAt = 1)
            val city = CivsRules.validate(h.city.copy(workers = listOf(worker),
                stock = if (automatic) Stock() else h.city.stock,
                inventory = if (automatic) CivInventory(CivStorageMode.PHYSICAL) else h.city.inventory,
                autonomy = if (automatic) CivAutonomy() else null))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.citizens.awakeResidentIds(city.id) } returns setOf(worker.id)
            h.paper.addPlayer("ResidentHealthWitness").teleport(Location(h.world, 1.5, 65.0, .5))
            h.start()
            h.paper.performTicks(20)
            repeat(3) {
                h.time += 1_000
                h.paper.performTicks(20)
                h.saves shouldBe emptyList()
            }
            h.time += 1_000
            h.paper.performTicks(20)
            h.saves.size shouldBe 1
            CivsResidents.health(h.saves.single().first.workers.single()) shouldBe 9.0
            CivsResidents.health(h.service.settlements().single().workers.single()) shouldBe 8.0
            var gameplayContinued = false
            h.tasks.runLater(1) { gameplayContinued = true }
            repeat(10) { h.time += 1_000; h.paper.performTicks(20) }
            gameplayContinued shouldBe true
            h.saves.size shouldBe 1
            CivsResidents.health(h.service.settlements().single().workers.single()) shouldBe 8.0
            h.commit()
            CivsResidents.health(h.service.settlements().single().workers.single()) shouldBe 9.0
            h.paper.performTicks(20)
            h.saves.size shouldBe 1
            repeat(3) { h.time += 1_000; h.paper.performTicks(20) }
            h.saves.size shouldBe 1
            h.time += 1_000
            h.paper.performTicks(20)
            h.saves.size shouldBe 2
            CivsResidents.health(h.saves.last().first.workers.single()) shouldBe 10.0
        }
    }
})

class CivsResidentFoodFairnessTest : FunSpec({
    for (firstBuffer in listOf("full", "unavailable")) test("native Auto food rotates past $firstBuffer storage without a second grant") {
        serviceScenario(nativeGeometry = true, storageFactory = { CivsStorage(it) { emptyArray() } }) { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val firstWorker = h.city.workers.first().copy(hunger = 0, homeId = hall.id)
            val first = CivsRules.validate(h.city.copy(name = "First", buildings = listOf(hall), workers = listOf(firstWorker),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
            val secondHall = hall.copy(id = UUID.randomUUID(), anchor = CivPosition(64, 64, 0),
                bounds = CivBox(CivPosition(62, 63, -2), CivPosition(66, 68, 2)),
                entrance = CivPosition(64, 64, -2), control = CivPosition(64, 64, 0))
            val secondWorker = firstWorker.copy(id = UUID.randomUUID(), name = "Hungry", homeId = secondHall.id)
            val second = CivsRules.validate(first.copy(id = UUID.randomUUID(), name = "Second", center = CivPosition(64, 64, 0),
                buildings = listOf(secondHall), workers = listOf(secondWorker)))
            h.world.loadChunk(4, 0)
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.world.getBlockAt(64, 64, 0).type = Material.BARREL
            val firstBarrel = (h.world.getBlockAt(0, 64, 0).state as org.bukkit.block.Barrel).inventory
            val secondBarrel = (h.world.getBlockAt(64, 64, 0).state as org.bukkit.block.Barrel).inventory
            if (firstBuffer == "full") repeat(firstBarrel.size) { firstBarrel.setItem(it, ItemStack(Material.STONE, 64)) }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(first, second))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.paper.addPlayer("NativeFoodWitness").teleport(Location(h.world, 1.5, 65.0, .5))
            // This case owns supply fairness; automatic expansion has its own city-save tests.
            CivsAutonomousDevelopment::class.java.getDeclaredField("nextAutonomyAt").also { it.isAccessible = true }
                .setLong(autonomousDevelopment(h.service), Long.MAX_VALUE)
            h.start()
            if (firstBuffer == "unavailable") h.world.unloadChunk(0, 0)
            val pulse = CivsResidentSupplies::class.java.getDeclaredMethod("pulseAutoSupplies").also { it.isAccessible = true }
            pulse.invokeService(h.service)
            h.saves shouldBe emptyList()
            // Await the real budgeted capture without advancing the population or grant clocks.
            val observation = CivsResidentSupplies::class.java.getDeclaredMethod("autoSupplyObservation", Settlement::class.java)
                .also { it.isAccessible = true }
            h.tickUntil("second town native supply capture") { observation.invokeService(h.service, second) != null }
            h.time += 1_000
            pulse.invokeService(h.service)
            h.tickUntil("second town native food receipt") { h.saves.isNotEmpty() }
            h.saves.single().first.id shouldBe second.id
            h.saves.single().first.stock shouldBe Stock()
            secondBarrel.contents.filterNotNull().filter { it.type == Material.BREAD }.sumOf { it.amount } shouldBe 4
            repeat(3) { h.time += 1_000; pulse.invokeService(h.service) }
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            h.commit()
            h.service.settlements().first { it.id == first.id }.stock shouldBe Stock()
        }
    }
})
