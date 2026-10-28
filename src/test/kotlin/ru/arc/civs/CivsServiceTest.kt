package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import io.papermc.paper.connection.PlayerGameConnection
import io.papermc.paper.dialog.DialogResponseView
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.block.data.Ageable
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.util.RayTraceResult
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.api.*
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogRecord

class CivsServiceTest : FunSpec({
    test("operator terrain capture rejects an unready service and a worker outside the selected city without native access") {
        serviceScenario { h ->
            val worker = h.city.workers.first()
            val origin = CivPosition(8, 55, 8)
            var feetReads = 0
            every { h.citizens.residentFeet(any()) } answers { feetReads++; null }
            h.service.debugTerrainAt(h.city.id, worker.id, origin)["error"] shouldBe "not-ready"
            h.start()
            val cities = h.service.settlements()
            val saves = h.saves.toList()
            val reads = feetReads
            h.service.debugTerrainAt(UUID.randomUUID(), worker.id, origin)["error"] shouldBe "city-not-found"
            h.service.debugTerrainAt(h.city.id, UUID.randomUUID(), origin)["error"] shouldBe "worker-not-found"
            h.service.settlements() shouldBe cities
            h.saves shouldBe saves
            feetReads shouldBe reads
        }
    }

    test("a staffed tavern serves existing bread through the ordinary pickup receipt with a hall fallback") {
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
            h.service.residentSupplySource(ready.copy(workers = listOf(guest)), guest) shouldBe hall.id
            val pickup = h.service.prepareResidentSupplies(ready.id, guest.id, tavern.id)
            h.tick(); h.commit()
            val after = pickup.join()
            after.inventory!!.buildingStock(tavern.id) shouldBe emptyMap()
            after.inventory!!.buildingStock(hall.id) shouldBe mapOf("BREAD" to 8L)
            CivsEquipment.plainCount(after.workers.first { it.id == guest.id }, "BREAD") shouldBe 4L
            after.workers.first { it.id == innkeeper.id } shouldBe innkeeper
        }
    }

    test("unfunded research journals the exact university barrel bill once before any researcher is assigned") {
        serviceScenario { h ->
            val university = h.city.buildings.last().copy(type = BuildingType.UNIVERSITY,
                control = CivPosition(10, 63, 0))
            val prerequisites = listOf(BuildingType.FORESTER, BuildingType.QUARRY).mapIndexed { index, type ->
                university.copy(id = UUID.randomUUID(), type = type, anchor = CivPosition(20 + index * 10, 64, 0),
                    bounds = CivBox(CivPosition(18 + index * 10, 63, -2), CivPosition(22 + index * 10, 68, 2)),
                    entrance = CivPosition(20 + index * 10, 64, -2), control = CivPosition(20 + index * 10, 63, 0))
            }
            val base = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), workers = emptyList(),
                buildings = listOf(h.city.buildings.first(), university) + prerequisites)
            val before = CivsResearch.begin(base, CivResearchId.MATERIALS, UUID.randomUUID(), h.time)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, ItemStack(Material.BOOK, 1))
            barrel.setItem(1, ItemStack(Material.PAPER, 8))
            barrel.setItem(2, ItemStack(Material.COBBLESTONE, 8))
            barrel.setItem(3, ItemStack(Material.DIAMOND, 2))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.paper.addPlayer("ResearchFundingWitness").teleport(Location(h.world, .5, 65.0, .5))
            val pulse = CivsService::class.java.getDeclaredMethod("pulseResearch").also { it.isAccessible = true }
            pulse.invoke(h.service)
            val tx = requireNotNull(receipt)
            tx.changes.map { it.container }.distinct() shouldBe listOf(university.control)
            tx.changes.map { it.before!!.material to it.before!!.amount }.toMap() shouldBe
                mapOf("BOOK" to 1, "PAPER" to 8, "COBBLESTONE" to 8)
            tx.changes.all { it.after == null } shouldBe true
            barrel.getItem(0)!!.amount shouldBe 1
            h.service.city(h.player)!!.research!!.project!!.funded shouldBe false
            h.saves shouldBe emptyList()
            pulse.invoke(h.service)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            journal.complete(tx)
            h.tickUntil("exact research debit and pending city credit") { h.saves.isNotEmpty() }
            barrel.getItem(0) shouldBe null
            barrel.getItem(1) shouldBe null
            barrel.getItem(2) shouldBe null
            barrel.getItem(3) shouldBe ItemStack(Material.DIAMOND, 2)
            h.service.city(h.player)!!.research!!.project!!.funded shouldBe false
            tx.after.research!!.project!!.funded shouldBe true
            tx.after.workers shouldBe emptyList()
            tx.after.stock shouldBe Stock()
            h.commit()
            pulse.invoke(h.service); h.tick()
            val funded = h.service.city(h.player)!!
            funded.research!!.project!!.funded shouldBe true
            funded.research!!.project!!.workDone shouldBe 0
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(tx.id) }
        }
    }

    test("research session requires the selected observed trip and commits progress cycle and training together once") {
        serviceScenario { h ->
            val university = h.city.buildings.last().copy(type = BuildingType.UNIVERSITY, control = CivPosition(10, 63, 0))
            val prerequisites = listOf(BuildingType.FORESTER, BuildingType.QUARRY).mapIndexed { index, type ->
                university.copy(id = UUID.randomUUID(), type = type, anchor = CivPosition(20 + index * 10, 64, 0),
                    bounds = CivBox(CivPosition(18 + index * 10, 63, -2), CivPosition(22 + index * 10, 68, 2)),
                    entrance = CivPosition(20 + index * 10, 64, -2), control = CivPosition(20 + index * 10, 63, 0))
            }
            val base = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), university) + prerequisites,
                workers = listOf(h.city.workers.first().copy(assignment = university.id, nextDeliveryAt = 1)))
            val begun = CivsResearch.begin(base, CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
            val ready = CivsResearch.fund(begun, begun.research!!.project!!.id, 2_000, nativePaid = true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.start()
            val worker = ready.workers.single()
            val plan = requireNotNull(citizenPlan(ready, worker))
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete() = deliver.invoke(h.service, ready.id, worker.id, university.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            every { h.citizens.requireSelectedWorkDelivery(ready.id, worker.id, plan.token) } throws CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "route-unreachable"
            h.saves shouldBe emptyList()
            every { h.citizens.requireSelectedWorkDelivery(ready.id, worker.id, plan.token) } returns Unit
            val completed = complete()
            completed.isDone shouldBe false
            h.service.city(h.player) shouldBe ready
            h.saves.single().first.let { saved ->
                saved.research!!.project!!.workDone shouldBe 1
                saved.workers.single().completedCycles shouldBe 1L
                saved.workers.single().skills shouldBe mapOf("UNIVERSITY" to 1L)
                saved.stock shouldBe Stock()
                saved.inventory shouldBe ready.inventory
                saved.revision shouldBe ready.revision + 1
            }
            h.commit(); completed.join()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("research keeps the explicit Auto city public for reads and rechecks admin permission after a pending save") {
        serviceScenario { h ->
            val university = h.city.buildings.last().copy(type = BuildingType.UNIVERSITY, control = CivPosition(10, 63, 0))
            val prerequisites = listOf(BuildingType.FORESTER, BuildingType.QUARRY).mapIndexed { index, type ->
                university.copy(id = UUID.randomUUID(), type = type, anchor = CivPosition(20 + index * 10, 64, 0),
                    bounds = CivBox(CivPosition(18 + index * 10, 63, -2), CivPosition(22 + index * 10, 68, 2)),
                    entrance = CivPosition(20 + index * 10, 64, -2), control = CivPosition(20 + index * 10, 63, 0))
            }
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL), workers = emptyList(),
                buildings = listOf(h.city.buildings.first(), university) + prerequisites)
            val auto = personal.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), name = "Independent research",
                stock = Stock(96, 96, 24), inventory = null, autonomy = CivAutonomy(paused = true))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal, auto))
            h.start()
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.service.research(h.player, auto.id).cityId shouldBe auto.id
            h.service.industry(h.player, auto.id).autonomous shouldBe true
            CivsDiagnostics.rejection(shouldThrow<CompletionException> {
                h.service.beginResearch(h.player, auto.id, CivResearchId.MATERIALS).join()
            })?.reason shouldBe "not-authorized"
            val otherWorld = h.paper.addSimpleWorld("research_other")
            every { h.player.world } returns otherWorld
            shouldThrow<CivsRejection> { h.service.research(h.player, auto.id) }.reason shouldBe "not-authorized"
            every { h.player.world } returns h.world
            every { h.player.hasPermission("arccivs.admin") } returns true
            val mutate = CivsService::class.java.declaredMethods.single { it.name == "mutate" }.also { it.isAccessible = true }
            val unrelated: (Settlement) -> Settlement = { it.copy(revision = it.revision + 1) }
            val pending = mutate.invoke(h.service, auto.id, false, unrelated) as CompletableFuture<*>
            val queued = h.service.beginResearch(h.player, auto.id, CivResearchId.MATERIALS)
            queued.isDone shouldBe false
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.commit(); pending.join()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(queued) })?.reason shouldBe "not-authorized"
            h.saves.size shouldBe 1
            h.service.settlements().single { it.id == auto.id }.research shouldBe null
            h.service.city(h.player) shouldBe personal
            val acceptedPersonal = h.service.beginResearch(h.player, personal.id, CivResearchId.MATERIALS)
            h.saves.last().first.id shouldBe personal.id
            h.saves.last().first.research!!.project!!.cost.materials shouldBe mapOf("BOOK" to 1L, "PAPER" to 8L, "COBBLESTONE" to 8L)
            h.commit(); acceptedPersonal.join()
            h.service.settlements().single { it.id == auto.id }.research shouldBe null
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("both asynchronous persistence fences hold city authority and delay the exact ground credit") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val source = CompletableFuture<Unit>()
            val after = CompletableFuture<Unit>()
            storage.persistCollectedNative = { _, beforeJournal -> if (beforeJournal) source else after }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            collecting.isDone shouldBe false
            item.isValid shouldBe true
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.service.collectionAvailable(before.id) shouldBe false
            val duplicate = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.await(duplicate) })?.reason shouldBe "city-busy"
            source.complete(Unit)
            h.tickUntil("native ground debit") { !item.isValid }
            item.isValid shouldBe false
            h.saves shouldBe emptyList()
            collecting.isDone shouldBe false
            after.complete(Unit)
            h.tickUntil("city save after native and marker ACK") { h.saves.isNotEmpty() }
            val saved = h.saves.single()
            saved.first.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
            saved.second.complete(saved.first)
            h.await(collecting).revision shouldBe before.revision + 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("a native timeout after ground debit retries the same locked receipt after five seconds and credits once") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val afterAttempts = mutableListOf<UUID>()
            storage.persistCollectedNative = { tx, beforeJournal ->
                if (beforeJournal) CompletableFuture.completedFuture(Unit)
                else {
                    afterAttempts += tx.id
                    if (afterAttempts.size == 1) CompletableFuture.failedFuture(CivsNativePersistenceRetry("Native persistence timed out"))
                    else CompletableFuture.completedFuture(Unit)
                }
            }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            shouldThrow<CompletionException> { h.await(collecting) }
            item.isValid shouldBe false
            h.saves shouldBe emptyList()
            storage.debug(before.id)["total"] shouldBe 1
            verify(exactly = 0) { h.store.markConsumedItems(any()) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
            h.time += 4_999
            h.tick()
            afterAttempts.size shouldBe 1
            h.time++
            h.tickUntil("retry native ACK and one recovered city credit") { h.saves.isNotEmpty() }
            afterAttempts.size shouldBe 2
            afterAttempts.distinct().size shouldBe 1
            val saved = h.saves.single()
            saved.first.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
            saved.second.complete(saved.first)
            h.tickUntil("recovered receipt retirement") { storage.debug(before.id)["total"] == 0 }
            h.service.city(h.player)!!.revision shouldBe before.revision + 1
            h.time += 10_000
            h.tick()
            afterAttempts.size shouldBe 2
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.markConsumedItems(any()) }
            verify(exactly = 1) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("unknown consumed marker outcome retains the debited receipt and cannot credit the bag") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also {
                storage = it; it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
            }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val marker = CompletableFuture<CivConsumedItemIndex>()
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.markConsumedItems(any()) } returns marker
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            repeat(6) { h.paper.performTicks(1) }
            item.isValid shouldBe false
            h.saves shouldBe emptyList()
            collecting.isDone shouldBe false
            marker.completeExceptionally(java.io.IOException("Unknown atomic marker rename outcome"))
            shouldThrow<CompletionException> { h.await(collecting) }
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
            verify(exactly = 1) { h.store.markConsumedItems(any()) }
            storage.debug(before.id)["total"] shouldBe 1
            h.time += 10_000
            h.tick()
            h.saves shouldBe emptyList()
            verify(exactly = 1) { h.store.markConsumedItems(any()) }
        }
    }

    for (phase in listOf("before", "after")) test("city revision drift during $phase disk acknowledgement cannot publish a prepared credit") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val pending = CompletableFuture<Unit>()
            storage.persistCollectedNative = { _, beforeJournal ->
                if (beforeJournal == (phase == "before")) pending else CompletableFuture.completedFuture(Unit)
            }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            repeat(3) { h.paper.performTicks(1) }
            @Suppress("UNCHECKED_CAST")
            val current = CivsService::class.java.getDeclaredField("cities").apply { isAccessible = true }
                .get(h.service) as MutableMap<UUID, Settlement>
            current[before.id] = before.copy(revision = before.revision + 1)
            pending.complete(Unit)
            shouldThrow<CompletionException> { h.await(collecting) }
            h.saves shouldBe emptyList()
            item.isValid shouldBe (phase == "before")
            verify(exactly = if (phase == "before") 0 else 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
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
                storage.persistCollectedNative = { _, _ ->
                    order += if (item.isValid) "source-flush" else "after-flush"
                    CompletableFuture.completedFuture(Unit)
                }
                var receipt: CivInventoryTransaction? = null
                val journal = CompletableFuture<CivInventoryTransaction>()
                val retirement = CompletableFuture<Boolean>()
                every { h.store.saveInventoryTransaction(any()) } answers { order += "journal"; receipt = firstArg(); journal }
                every { h.store.save(any()) } answers {
                    order += "city"
                    CompletableFuture<Settlement>().also { h.saves += firstArg<Settlement>() to it }
                }
                every { h.store.markConsumedItems(any()) } answers {
                    order += "marker"
                    CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY.adding(listOf(item.uniqueId)))
                }
                every { h.store.deleteInventoryTransaction(any()) } answers { order += "retire"; retirement }
                h.start()
                val collected = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
                h.paper.performTicks(1) // The acknowledged source returns to city authority before journalling.
                order shouldBe listOf("source-flush", "journal")
                item.isValid shouldBe true
                events shouldBe emptyList()
                journal.complete(receipt!!)
                h.tickUntil("city save after journal and marker") { h.saves.isNotEmpty() }
                order shouldBe listOf("source-flush", "journal", "after-flush", "marker", "city")
                item.isValid shouldBe false
                h.service.settlements().single().workers.single().hasCollectedItems shouldBe null
                h.saves.single().let { it.second.complete(it.first) }
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
                events.single()["transaction"] shouldBe receipt!!.id
                events.single()["items"] shouldBe 8
                events.single()["slots"] shouldBe 1
                h.time += 4_999
                h.service.collectionAvailable(before.id) shouldBe false
                h.time++
                h.service.collectionAvailable(before.id) shouldBe true
            }
        } finally { unmockkObject(CivsDiagnostics) }
    }

    for (failurePhase in listOf("before", "after")) test("failed $failurePhase native fence never credits the resident") {
        lateinit var storage: CivsStorage
        serviceScenario(nativeGeometry = true, storageFactory = { plugin ->
            CivsStorage(plugin) { emptyArray() }.also { storage = it }
        }) { h ->
            val worker = h.city.workers.first()
            val before = h.city.copy(workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 2.5, 64.0, .5)
            val item = civsGroundFixture(h.paper, h.world, Location(h.world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            var fences = 0
            storage.persistCollectedNative = { _, _ ->
                if (++fences == if (failurePhase == "before") 1 else 2) CompletableFuture.failedFuture(IllegalStateException("flush failed"))
                else CompletableFuture.completedFuture(Unit)
            }
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val collecting = h.service.pickupGround(before.id, worker.id, listOf(item.uniqueId))
            shouldThrow<CompletionException> { h.await(collecting) }
            h.saves shouldBe emptyList()
            h.service.city(h.player)!!.workers.single() shouldBe worker
            item.isValid shouldBe (failurePhase == "before")
            verify(exactly = if (failurePhase == "before") 0 else 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
            h.service.corpseSettlements().size shouldBe if (failurePhase == "after") 0 else 1
            h.time += 10_000
            h.tick()
            fences shouldBe if (failurePhase == "before") 1 else 2
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

    test("home deposit persists native exact slots before bag debit and never changes abstract stock") {
        lateinit var storage: CivsStorage
        val fences = mutableListOf<Int>()
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
            storage.persistCollectedNative = { _, _ ->
                fences += barrel.getItem(0)?.amount ?: 0
                CompletableFuture.completedFuture(Unit)
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.citizens.residentCollectionFeet(worker.id, any()) } returns Location(h.world, 1.5, 64.0, .5)
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val depositing = h.service.depositCollected(before.id, worker.id, hall.control)
            repeat(4) { h.paper.performTicks(1) }
            fences shouldBe listOf(0, 8)
            barrel.getItem(0) shouldBe ItemStack(Material.COAL, 8)
            val saved = h.saves.single()
            saved.first.stock shouldBe before.stock
            saved.first.inventory shouldBe null
            saved.first.workers.single().hasCollectedItems shouldBe false
            saved.second.complete(saved.first)
            h.await(depositing).workers.single().items!!.all { it == null } shouldBe true
        }
    }

    test("public building inspection is nearby and autonomous without widening foreign personal access") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val personal = h.city.copy(owner = UUID.randomUUID(), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(hall), workers = emptyList())
            val auto = personal.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), inventory = null,
                stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal, auto))
            val visitor = h.paper.addPlayer("Inspector").apply {
                addAttachment(h.plugin, "arccivs.use", true)
                teleport(Location(h.world, .5, 64.0, 2.5))
            }
            h.start()
            h.service.inspectBuilding(visitor, auto.id, hall.id) shouldBe auto
            shouldThrow<CivsRejection> { h.service.inspectBuilding(visitor, personal.id, hall.id) }.reason shouldBe "not-authorized"
            visitor.addAttachment(h.plugin, "arccivs.admin", true)
            shouldThrow<CivsRejection> { h.service.inspectBuilding(visitor, personal.id, hall.id) }.reason shouldBe "not-authorized"
            visitor.teleport(Location(h.world, 30.0, 64.0, 0.0))
            shouldThrow<CivsRejection> { h.service.inspectBuilding(visitor, auto.id, hall.id) }.reason shouldBe "too-far"
        }
    }

    for (autonomous in listOf(false, true)) test("scroll resurrection is one durable exact-item operation autonomous=$autonomous") {
        serviceScenario(nativeGeometry = true, storageFactory = {
            CivsStorage(it, ru.arc.paper.playerstate.PaperPlayerDataPersistence { }) { emptyArray() }
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

    test("historical access repair precedes growth with empty stock and publishes no construction or resource credit") {
        serviceScenario { h ->
            val city = h.city.copy(autonomy = CivAutonomy(), stock = Stock(), workers = emptyList())
            val older = city.buildings.first()
            val offender = city.buildings.last()
            val write = CivPlanBlock(older.entrance, "minecraft:air", "minecraft:coarse_dirt")
            val plan = CivPlacementPlan(offender.id, city.id, city.worldId, offender, emptyList(),
                terrain = CivTerraformPlan(offender.bounds, listOf(write), listOf(offender.entrance), 63, 0, 1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(offender.id) } returns CompletableFuture.completedFuture(plan)
            var repair = CompletableFuture<Map<String, Int>>()
            every { h.worldAdapter.repairAutonomousAccess(city, any()) } answers { repair }
            h.start()
            h.paper.addPlayer("AccessOwner").teleport(Location(h.world, 0.5, 65.0, 0.5))
            val pulse = CivsService::class.java.getDeclaredMethod("pulseAutonomousAccessRepair").also { it.isAccessible = true }
            val ready = CivsService::class.java.getDeclaredMethod("autonomousAccessReady", Settlement::class.java)
                .also { it.isAccessible = true }
            pulse.invoke(h.service); h.tick()
            ready.invoke(h.service, city) shouldBe false
            verify(exactly = 1) { h.worldAdapter.repairAutonomousAccess(city, listOf(
                CivAutonomousAccessRepair(older.id, offender.id, older.entrance,
                    write.copy(before = write.after, after = write.before)))) }
            h.saves shouldBe emptyList()
            repair.complete(mapOf("changed" to 0, "unchanged" to 0, "conflicts" to 0, "denied" to 1)); h.tick()
            ready.invoke(h.service, city) shouldBe false
            pulse.invoke(h.service)
            verify(exactly = 1) { h.worldAdapter.repairAutonomousAccess(any(), any()) }
            repair = CompletableFuture()
            h.time += 30_001
            pulse.invoke(h.service); h.tick()
            repair.complete(mapOf("changed" to 1, "unchanged" to 0, "conflicts" to 0, "denied" to 0)); h.tick()
            ready.invoke(h.service, city) shouldBe true
            pulse.invoke(h.service); h.tick()
            verify(exactly = 2) { h.worldAdapter.repairAutonomousAccess(any(), any()) }
            h.service.settlements().single().stock shouldBe Stock()
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any()) }
        }
    }

    for (atInfrastructureCap in listOf(false, true))
    test("a failed full plan backs off while ordinary growth remains admissible atInfrastructureCap=$atInfrastructureCap") {
        mockkConstructor(CivsAutonomousPlacement::class)
        try {
            every { anyConstructed<CivsAutonomousPlacement>().plan(any()) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-no-site", mapOf("type" to "SMELTERY")))
            every { anyConstructed<CivsAutonomousPlacement>().search(any(), any(), any(), any()) } returns CompletableFuture()
            serviceScenario { h ->
                val oldWalls = if (!atInfrastructureCap) emptyList() else (0 until CivsRules.MAX_INFRASTRUCTURE).map { i ->
                    val at = CivPosition(30 + i % 12 * 2, 64, 30 + i / 12 * 2)
                    CivBuilding(UUID.randomUUID(), BuildingType.WALL, 1, at, 0, "civs/infrastructure/v1/wall/fixture",
                        CivBox(at, at.copy(y = 67)), at.copy(z = at.z - 1), BuildingPhase.ACTIVE)
                }
                val city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), workers = emptyList(),
                    buildings = h.city.buildings + oldWalls))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
                h.start()
                h.paper.addPlayer("GrowthObserver").teleport(Location(h.world, 0.5, 65.0, 0.5))
                val repair = CivsService::class.java.getDeclaredMethod("pulseAutonomousAccessRepair").also { it.isAccessible = true }
                val pulse = CivsService::class.java.getDeclaredMethod("pulseAutonomy").also { it.isAccessible = true }
                repair.invoke(h.service); h.tick()
                h.time += 5_001
                pulse.invoke(h.service); h.tick()
                verify(exactly = 1) { anyConstructed<CivsAutonomousPlacement>().plan(any()) }
                verify(exactly = 0) { anyConstructed<CivsAutonomousPlacement>().search(any(), any(), any(), any()) }
                h.time += 30_001
                pulse.invoke(h.service); h.tick()
                verify(exactly = 1) { anyConstructed<CivsAutonomousPlacement>().plan(any()) }
                verify(exactly = 1) { anyConstructed<CivsAutonomousPlacement>().search(any(), BuildingType.BUILDER_HUT, 1, any()) }
                val after = h.service.settlements().single()
                after.buildings shouldBe city.buildings
                after.stock shouldBe city.stock
                after.autonomy?.plan shouldBe null
                h.saves shouldBe emptyList()
            }
        } finally { unmockkConstructor(CivsAutonomousPlacement::class) }
    }

    test("autonomous production requires entrance arrival and a durable unique trip receipt without physical inventory") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 64, 0))
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY,
                control = CivPosition(10, 64, 0))
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), buildings = listOf(hall, quarry),
                workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete() = deliver.invoke(h.service, village.id, worker.id, quarry.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val token = CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null)
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } throws CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "route-unreachable"
            h.saves shouldBe emptyList()
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } returns requireNotNull(citizenPlan(village, worker))
            val delivered = complete()
            delivered.isDone shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
            h.commit(); delivered.join()
            val saved = h.service.settlements().single()
            saved.stock shouldBe village.stock.copy(stone = village.stock.stone + CivsRules.deliveryAmount(quarry))
            saved.workers.single().completedCycles shouldBe 1L
            saved.inventory shouldBe null
            saved.workers.single().items shouldBe null
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.citizens.requireStorageArrival(any(), any(), any()) }
        }
    }

    test("pending autonomous goods require arrival and unload once without another production reward") {
        serviceScenario { h ->
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY, control = CivPosition(10, 64, 0))
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1,
                pendingStock = Stock(stone = 32))
            val village = h.city.copy(autonomy = CivAutonomy(), stock = Stock(),
                buildings = listOf(h.city.buildings.first(), quarry), workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val token = CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null)
            fun complete() = deliver.invoke(h.service, village.id, worker.id, quarry.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            every { h.citizens.pendingStockDelivery(village.id, worker.id) } returns worker.pendingStock
            every { h.citizens.requirePendingStockDelivery(village.id, worker.id, token) } throws CivsRejection("route-unreachable")
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "route-unreachable"
            h.saves shouldBe emptyList()
            every { h.citizens.requirePendingStockDelivery(village.id, worker.id, token) } returns requireNotNull(worker.pendingStock)
            val delivery = complete()
            delivery.isDone shouldBe false
            h.service.settlements().single() shouldBe village
            h.commit(); delivery.join()
            val saved = h.service.settlements().single()
            saved.stock shouldBe Stock(stone = 32)
            saved.workers.single().let {
                it.pendingStock shouldBe null
                it.completedCycles shouldBe worker.completedCycles
                it.skills shouldBe worker.skills
            }
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { complete().join() })?.reason shouldBe "stale-delivery"
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.citizens.requireAutonomousDelivery(any(), any(), any()) }
        }
    }

    test("autonomous delivery rechecks observed arrival after waiting for another city revision") {
        serviceScenario { h ->
            val quarry = h.city.buildings.last().copy(type = BuildingType.QUARRY,
                control = CivPosition(10, 67, 0))
            val worker = h.city.workers.first().copy(assignment = quarry.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), buildings = listOf(h.city.buildings.first(), quarry),
                workers = listOf(worker))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val mutate = CivsService::class.java.declaredMethods.single { it.name == "mutate" }.also { it.isAccessible = true }
            val unrelated: (Settlement) -> Settlement = { it.copy(stock = it.stock.copy(stone = it.stock.stone + 1),
                revision = it.revision + 1) }
            val pending = mutate.invoke(h.service, village.id, false, unrelated) as CompletableFuture<*>
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val token = CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null)
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } returns requireNotNull(citizenPlan(village, worker))
            val queued = deliver.invoke(h.service, village.id, worker.id, quarry.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            queued.isDone shouldBe false
            verify(exactly = 0) { h.citizens.requireAutonomousDelivery(any(), any(), any()) }
            // The resident leaves the endpoint while the unrelated revision is still being persisted.
            every { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) } throws CivsRejection("route-unreachable")
            h.commit(); pending.join()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { queued.join() })?.reason shouldBe "route-unreachable"
            verify(exactly = 1) { h.citizens.requireAutonomousDelivery(village.id, worker.id, token) }
            h.saves.size shouldBe 1
            h.service.settlements().single().stock shouldBe village.stock.copy(stone = village.stock.stone + 1)
            h.service.settlements().single().workers.single().completedCycles shouldBe 0L
        }
    }

    test("Auto field scenery uses one native validation and an ordinary save without an inventory journal") {
        serviceScenario(nativeGeometry = true) { h ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(30, 63, 0))
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(h.city.buildings.first(), farm), workers = listOf(worker))
            for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                h.world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                h.world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = Bukkit.createBlockData("minecraft:wheat[age=1]")
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val crop = CivsWorksiteLayouts.field(farm).first().let { it.copy(y = it.y + 1) }
            h.world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            // This fixture checks native application and saving, independently of host/MockK speed.
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(worksites, { 0L })
            repeat(16) { worksites.pulse(listOf(village)) }
            val work = requireNotNull(worksites.plan(village, worker))
            every { h.citizens.autonomousVisualWork(village.id, worker.id) } returns work
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val pending = deliver.invoke(h.service, village.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            h.saves.single().first shouldBe village.copy(revision = village.revision + 1)
            work.changes.forEach { cell ->
                h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after
            }
            pending.isDone shouldBe false
            verify(exactly = 1) { h.citizens.requireWorldWorkArrival(village.id, worker.id, work) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
            h.commit(); h.await(pending)
            h.service.settlements().single() shouldBe village.copy(revision = village.revision + 1)
            (worksites.plan(h.service.settlements().single(), worker) == work) shouldBe false
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.store.deleteInventoryTransaction(any()) }
        }
    }

    for (alreadySaved in listOf(false, true))
    test("legacy Auto scenery retirement waits for disk without replaying native or city state saved=$alreadySaved") {
        serviceScenario { h ->
            val (fixture, work) = visualReceiptFixture("miner")
            val before = fixture.copy(worldId = h.world.uid, owner = h.owner)
            val receipt = autonomousVisualTransaction(before, work).validated()
            val current = (if (alreadySaved) receipt.after else before).let {
                it.copy(autonomy = requireNotNull(it.autonomy).copy(paused = true))
            }
            val cell = work.changes.single()
            h.world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
            val block = h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            block.type = Material.DIAMOND_BLOCK // An obsolete scenery receipt must not undo later native edits.
            val retirement = CompletableFuture<Boolean>()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns retirement
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(current))
            val started = h.service.start()
            h.tick()
            started.isDone shouldBe false
            verify(exactly = 0) { h.store.load(); h.store.save(any()) }
            block.type shouldBe Material.DIAMOND_BLOCK
            retirement.complete(true)
            h.await(started) shouldBe Unit
            h.service.ready() shouldBe true
            h.service.settlements().single() shouldBe current
            val overview = h.service.debugState("overview", current.id, null)
            overview["inventory_recovery_total"] shouldBe 0
            overview["blocked_recovery_total"] shouldBe 0
            ((overview["cities"] as List<*>).single() as Map<*, *>)["unhealthy"] shouldBe false
            block.type shouldBe Material.DIAMOND_BLOCK
            h.saves shouldBe emptyList()
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.citizens.requireWorldWorkArrival(any(), any(), any()) }
        }
    }

    test("retiring legacy Auto scenery never discards an ordinary empty inventory receipt") {
        serviceScenario { h ->
            val (fixture, work) = visualReceiptFixture("miner")
            val before = fixture.copy(worldId = h.world.uid)
            val cosmetic = autonomousVisualTransaction(before, work).validated()
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL), workers = emptyList())
            val ordinary = CivInventoryTransaction(UUID.randomUUID(), personal.id, personal.revision,
                personal.copy(revision = personal.revision + 1), emptyList()).validated()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(cosmetic, ordinary))
            every { h.store.deleteInventoryTransaction(cosmetic.id) } returns CompletableFuture.completedFuture(true)
            every { h.store.deleteInventoryTransaction(ordinary.id) } returns CompletableFuture.completedFuture(true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal,
                before.copy(autonomy = requireNotNull(before.autonomy).copy(paused = true))))
            h.start()
            h.tickUntil("ordinary receipt still follows inventory recovery") { h.saves.isNotEmpty() }
            h.saves.single().first shouldBe ordinary.after
            verify(exactly = 1) { h.store.deleteInventoryTransaction(cosmetic.id) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(ordinary.id) }
            h.commit()
            verify(exactly = 1) { h.store.deleteInventoryTransaction(ordinary.id) }
        }
    }

    test("legacy Auto scenery retirement reports real file failures before loading cities") {
        serviceScenario { h ->
            val (city, work) = visualReceiptFixture("miner")
            val receipt = autonomousVisualTransaction(city, work).validated()
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns
                CompletableFuture.failedFuture(java.io.IOException("Cannot retire scenery journal"))
            val started = h.service.start()
            h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            h.service.unavailableReason() shouldBe "storage-unavailable"
            verify(exactly = 0) { h.store.load(); h.store.save(any()) }
        }
    }

    test("a forged value-bearing visual receipt is never retired as scenery") {
        serviceScenario { h ->
            val (city, work) = visualReceiptFixture("farmer")
            val original = autonomousVisualTransaction(city, work)
            val forged = original.copy(autonomousVisual = requireNotNull(original.autonomousVisual).copy(
                work = work.copy(inputs = mapOf("WHEAT_SEEDS" to 1L))))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(forged))
            val started = h.service.start()
            h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()); h.store.load() }
        }
    }

    test("stale Auto native preflight clears its admitted work so another farmer can replan without a payout") {
        serviceScenario(nativeGeometry = true) { h ->
            val farm = spatialBuilding("medievaloak", "farmer", 1, CivPosition(30, 63, 0))
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1)
            val village = h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(h.city.buildings.first(), farm), workers = listOf(worker))
            for (x in -1..4) for (z in -3..1) h.world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                h.world.getBlockAt(soil.x, soil.y, soil.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                h.world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=1]")
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { h.world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            val crop = CivsWorksiteLayouts.field(farm).first().let { it.copy(y = it.y + 1) }
            h.world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            val worksites = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }
                .get(h.service) as CivsWorksites
            // This receipt scenario is independent of host/MockK speed, like the native-application fixture above.
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(worksites, { 0L })
            repeat(16) { worksites.pulse(listOf(village)) }
            val work = requireNotNull(worksites.plan(village, worker))
            every { h.citizens.autonomousVisualWork(village.id, worker.id) } returns work
            h.world.getBlockAt(crop.x, crop.y, crop.z).blockData = org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=0]")
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            val rejected = deliver.invoke(h.service, village.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { rejected.join() })?.reason shouldBe "stale-worksite"
            worksites.plan(village, worker) shouldBe null
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            val overview = h.service.debugState("overview", village.id, null)
            overview["inventory_recovery_total"] shouldBe 0
            ((overview["cities"] as List<*>).single() as Map<*, *>)["unhealthy"] shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
            h.world.getBlockAt(crop.x, crop.y, crop.z).type = Material.AIR
            repeat(16) { worksites.pulse(listOf(village)) }
            requireNotNull(worksites.plan(village, worker)).changes.single().position shouldBe crop
        }
    }

    test("autonomous towns do not hide or migrate the administrator personal settlement on load") {
        serviceScenario { h ->
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val autonomous = h.city.copy(id = UUID.randomUUID(), name = "Independent village", workers = emptyList(),
                autonomy = CivAutonomy(paused = true))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(autonomous, personal))
            h.start()
            h.service.city(h.player)?.id shouldBe personal.id
            h.service.settlements().single { it.id == autonomous.id }.inventory shouldBe null
            h.service.autonomyStatus(autonomous.id)?.stage shouldBe "paused"
            h.saves shouldBe emptyList()
            h.service.refreshCity(h.player).let { lookup -> h.tick(); lookup.join() }
            h.service.ready() shouldBe true
        }
    }

    test("admin manual founding accepts its chosen site instead of automatic natural terrain admission") {
        serviceScenario(empty = true) { h ->
            h.sitePreview()
            every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.failedFuture(CivsRejection("autonomy-terrain"))
            h.start()
            h.service.beginAutonomousSelection(h.player, "Chosen Village", "nordic").join()
            h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0))))
            val confirmed = h.service.confirmPreview(h.player)
            h.tick()
            confirmed.isCompletedExceptionally shouldBe false
            h.saves.single().first.name shouldBe "Chosen Village"
            h.commit(); h.commit(); h.await(confirmed)
            h.service.settlements().single().name shouldBe "Chosen Village"
            verify(exactly = 1) { h.store.savePlan(match { it.adminPlacement }) }
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) }
        }
    }

    for (autonomous in listOf(false, true))
    test("admin manual founding rechecks revoked permission after plan persistence autonomous=$autonomous") {
        serviceScenario(empty = true) { h ->
            h.sitePreview()
            val planSaved = CompletableFuture<CivPlacementPlan>()
            var captured: CivPlacementPlan? = null
            every { h.store.savePlan(any()) } answers { captured = firstArg(); planSaved }
            h.start()
            if (autonomous) h.service.beginAutonomousSelection(h.player, "Chosen Village", "nordic").join()
            else h.service.beginFoundingSelection(h.player, "Chosen Village", "nordic").join()
            h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0))))
            val confirmed = h.service.confirmPreview(h.player)
            h.tick()
            requireNotNull(captured).adminPlacement shouldBe true
            every { h.player.hasPermission("arccivs.admin") } returns false
            planSaved.complete(requireNotNull(captured)); h.tick()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { confirmed.join() })?.reason shouldBe "not-authorized"
            h.saves shouldBe emptyList()
            h.service.settlements() shouldBe emptyList()
        }
    }

    test("admin manual founding intent survives a waiting autonomous site with no foundation at construction admission") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            val site = queued.city.buildings.last()
            val city = queued.city.copy(autonomy = CivAutonomy(paused = true), inventory = null,
                stock = h.city.stock, constructionQueue = emptyList())
            val target = site.copy(phase = BuildingPhase.CONSTRUCTING)
            val design = queued.design.copy(id = site.id, building = target, terrain = null, adminPlacement = true)
            val captured = design.copy(id = UUID.randomUUID())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(site.id) } returns CompletableFuture.completedFuture(design)
            every { h.store.loadSchematic(site.id) } returns CompletableFuture.completedFuture(byteArrayOf(2))
            every { h.blueprints.recover(design) } returns CompletableFuture.completedFuture(mockk<ArcSchematicSnapshot> {
                every { blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
            })
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } returns
                CompletableFuture.completedFuture(captured)
            h.world.loadChunk(site.control!!.x shr 4, site.control.z shr 4)
            h.world.getBlockAt(site.control.x, site.control.y, site.control.z).type = Material.BARREL
            h.start()
            val activate = CivsService::class.java.getDeclaredMethod("activateBuilding", Settlement::class.java,
                CivBuilding::class.java, Player::class.java).also { it.isAccessible = true }
            val started = activate.invoke(h.service, city, site, null) as CompletableFuture<*>
            h.tick()
            started.isCompletedExceptionally shouldBe false
            verify(exactly = 1) { h.worldAdapter.captureManualFoundation(city.id, city.owner, city.worldId,
                target, any(), any(), site, false, true) }
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any()) }
            h.saves.single().first.construction!!.id shouldBe captured.id
            h.commit(); h.commit(); h.await(started)
            verify(exactly = 1) { h.store.savePlan(match { it.id == captured.id && it.adminPlacement }) }
        }
    }

    test("admin can start autonomous placement while retaining a personal town and cannot bypass revoked admin permission") {
        serviceScenario { h ->
            h.start()
            h.service.beginAutonomousSelection(h.player, "Free Village", "nordic").join()
            h.service.selection(h.player)?.type shouldBe BuildingType.TOWN_HALL
            h.service.city(h.player)?.id shouldBe h.city.id
            every { h.player.hasPermission("arccivs.admin") } returns false
            val denied = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { denied.join() })?.reason shouldBe "not-authorized"
            h.saves shouldBe emptyList()
        }
    }

    test("admin plan board placement keeps its explicit autonomous city through preview edits and queues one site without grants") {
        serviceScenario { h ->
            val board = ServiceAutoBoard(h)
            h.start()
            h.service.beginPlanBoardSelection(h.player, board.city.id).join()
            verify(exactly = 1) { h.boundary.pulse(h.player, board.city, true, null, null,
                board.city.buildings.associate { it.id to h.service.buildingStatus(it.id) }) }
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id, board.target)
            h.await(selected)
            h.service.preview(h.player)!!.autonomous shouldBe true
            h.await(h.service.rotatePreview(h.player, 90))
            h.await(h.service.nudgePreview(h.player, CivsPreviewMove.RIGHT))
            h.await(h.service.movePreview(h.player, board.target))
            h.service.city(h.player) shouldBe h.city
            val confirmed = h.service.confirmPreview(h.player)
            h.tick()
            h.saves shouldBe emptyList()
            board.capture.complete(board.plan())
            h.tickUntil("autonomous plan board city commit") { h.saves.isNotEmpty() }
            val proposed = h.saves.single().first
            proposed.id shouldBe board.city.id
            proposed.owner shouldBe board.city.owner
            proposed.buildings.last().type shouldBe BuildingType.PLAN_BOARD
            proposed.buildings.last().phase shouldBe BuildingPhase.SITE
            proposed.constructionQueue shouldBe listOf(board.blueprint.building.id)
            proposed.revision shouldBe board.city.revision + 1
            proposed.stock shouldBe board.city.stock
            proposed.inventory shouldBe null
            proposed.workers shouldBe board.city.workers
            proposed.immigration shouldBe board.city.immigration
            proposed.forestrySeeded shouldBe board.city.forestrySeeded
            proposed.settings shouldBe board.city.settings
            h.commit() // Durable SITE precedes the existing one-time native control grant.
            h.saves.size shouldBe 2
            h.commit()
            h.await(confirmed)
            h.service.city(h.player) shouldBe h.city
            h.service.settlements().size shouldBe 2
            val placed = h.service.settlements().single { it.id == board.city.id }
            placed.pendingControls shouldBe emptyList()
            placed.constructionQueue shouldBe listOf(board.blueprint.building.id)
            placed.stock shouldBe board.city.stock
            placed.workers shouldBe board.city.workers
            h.time += 10_000
            h.paper.performTicks(40)
            h.saves.size shouldBe 2 // Paused Auto defers the ordinary paid construction queue.
            verify(exactly = 1) { h.worldAdapter.captureAutonomousSite(board.city.id, board.city.owner, h.world.uid,
                board.blueprint.building, any(), any()) }
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), h.owner, any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.save(match { it.id == h.city.id }) }
        }
    }

    for (denial in listOf("admin", "use", "world", "foreign-personal", "missing-city"))
        test("explicit autonomous board entry rejects $denial without falling back to a personal city") {
            serviceScenario { h ->
                val board = ServiceAutoBoard(h)
                h.start()
                var cityId = board.city.id
                when (denial) {
                    "admin" -> every { h.player.hasPermission("arccivs.admin") } returns false
                    "use" -> every { h.player.hasPermission("arccivs.use") } returns false
                    "world" -> every { h.player.world } returns h.paper.addSimpleWorld("other")
                    "foreign-personal" -> {
                        // The selected Auto must not become a generic administrative building bypass.
                        cityId = UUID.randomUUID()
                        val foreign = h.city.copy(id = cityId, owner = UUID.randomUUID(), workers = emptyList(),
                            buildings = h.city.buildings.map { it.copy(id = UUID.randomUUID()) })
                        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city, board.city, foreign))
                        h.await(h.service.reload(h.player))
                    }
                    "missing-city" -> cityId = UUID.randomUUID()
                }
                val denied = h.service.beginPlanBoardSelection(h.player, cityId)
                denied.isCompletedExceptionally shouldBe true
                h.service.selection(h.player) shouldBe null
                h.service.city(h.player) shouldBe h.city
                h.saves shouldBe emptyList()
            }
        }

    for (changed in listOf("admin", "use", "world"))
        test("autonomous board preparation rechecks $changed after the asynchronous blueprint load") {
            serviceScenario { h ->
                val board = ServiceAutoBoard(h)
                val prepared = CompletableFuture<CivBlueprint>()
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns prepared
                h.start()
                h.service.beginPlanBoardSelection(h.player, board.city.id).join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id, board.target)
                when (changed) {
                    "admin" -> every { h.player.hasPermission("arccivs.admin") } returns false
                    "use" -> every { h.player.hasPermission("arccivs.use") } returns false
                    "world" -> every { h.player.world } returns h.paper.addSimpleWorld("other")
                }
                prepared.complete(board.blueprint)
                h.tick()
                selected.isCompletedExceptionally shouldBe true
                h.service.preview(h.player) shouldBe null
                h.saves shouldBe emptyList()
                verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            }
        }

    for (stage in listOf("preview", "capture", "file-write"))
        test("autonomous board confirmation rejects revoked admin authority at $stage before its city commit") {
            serviceScenario { h ->
                val board = ServiceAutoBoard(h)
                h.start()
                h.service.beginPlanBoardSelection(h.player, board.city.id).join()
                h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id, board.target))
                val fileWrite = CompletableFuture<Unit>()
                if (stage == "file-write") every { h.store.saveSchematic(any(), any()) } returns fileWrite
                if (stage == "preview") every { h.player.hasPermission("arccivs.admin") } returns false
                val confirmed = h.service.confirmPreview(h.player)
                if (stage == "capture") {
                    every { h.player.hasPermission("arccivs.admin") } returns false
                    board.capture.complete(board.plan())
                } else if (stage == "file-write") {
                    board.capture.complete(board.plan())
                    h.tick()
                    verify(exactly = 1) { h.store.saveSchematic(board.blueprint.building.id, any()) }
                    every { h.player.hasPermission("arccivs.admin") } returns false
                    fileWrite.complete(Unit)
                }
                h.tick()
                CivsDiagnostics.rejection(shouldThrow<CompletionException> { confirmed.join() })?.reason shouldBe "not-authorized"
                h.saves shouldBe emptyList()
                h.service.city(h.player) shouldBe h.city
                h.service.settlements().single { it.id == board.city.id } shouldBe board.city
                val control = board.blueprint.building.control!!
                h.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.AIR
                // The MockK actor is absent from onlinePlayers, so timer pulses cannot visit it.
                h.paper.server.onlinePlayers.contains(h.player) shouldBe false
                CivsService::class.java.getDeclaredMethod("refreshBoundary", Player::class.java)
                    .also { it.isAccessible = true }.invoke(h.service, h.player)
                verify(atLeast = 1) { h.boundary.pulse(h.player, null, false, null, null, emptyMap()) }
                verify(exactly = 0) { h.boundary.pulse(h.player, match<Settlement> { it.id == h.city.id }, any(), any(), any(), any()) }
            }
        }

    test("personal catalog plan board selection keeps ordinary site and player authority semantics") {
        serviceScenario { h ->
            val board = ServiceAutoBoard(h)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city))
            val point = CivPosition(6, 63, 10)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.GRASS_BLOCK
            every { h.player.location } returns Location(h.world, 6.0, 65.0, 6.0)
            every { h.player.eyeLocation } returns Location(h.world, 6.0, 66.62, 6.0)
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.PLAN_BOARD).join()
            h.await(h.service.selectGround(h.player, h.service.selection(h.player)!!.id, CivsGroundTarget(h.world.uid, point)))
            h.service.preview(h.player)!!.autonomous shouldBe false
            val confirmed = h.service.confirmPreview(h.player)
            h.tickUntil("personal board site commit") { h.saves.isNotEmpty() }
            h.saves.first().first.let {
                it.id shouldBe h.city.id
                it.constructionQueue shouldBe null
                it.stock shouldBe h.city.stock
                it.workers shouldBe h.city.workers
            }
            h.commit(); h.commit(); h.await(confirmed)
            h.service.city(h.player)!!.buildings.last().type shouldBe BuildingType.PLAN_BOARD
            verify(exactly = 0) { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { h.worldAdapter.captureSite(h.city.id, h.owner, h.world.uid, board.blueprint.building, any(), any(), any()) }
        }
    }

    test("autonomous pause is durable separately from the administrators personal town and rechecks permission") {
        serviceScenario { h ->
            val personal = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            val autonomous = h.city.copy(id = UUID.randomUUID(), workers = emptyList(), autonomy = CivAutonomy())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(personal, autonomous))
            h.start()
            val pause = h.service.setAutonomousPaused(h.player, autonomous.id, true)
            h.commit(); pause.join()
            h.service.city(h.player)?.id shouldBe personal.id
            h.service.settlements().single { it.id == autonomous.id }.autonomy?.paused shouldBe true
            h.service.settlements().single { it.id == autonomous.id }.stock shouldBe autonomous.stock
            every { h.player.hasPermission("arccivs.admin") } returns false
            val resume = h.service.setAutonomousPaused(h.player, autonomous.id, false)
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { resume.join() })?.reason shouldBe "not-authorized"
            h.saves.size shouldBe 1
        }
    }

    test("warehouse maintenance completes exceptionally when a delayed schematic outlives its building") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE,
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.WAREHOUSE, 1), control = CivPosition(10, 63, 0))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), warehouse))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val schematic = CompletableFuture<List<CivPlanBlock>>()
            every { h.blueprints.buildingStorageRepair(warehouse) } returns schematic
            h.start()
            val repair = h.service.repairBuildingStorage(city.id, warehouse.id)
            repair.isDone shouldBe false
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city.copy(
                buildings = listOf(city.buildings.first(), warehouse.copy(phase = BuildingPhase.SITE)))))
            val reload = h.service.reload(h.player)
            h.tick(); reload.join()
            schematic.complete(listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:bookshelf", "minecraft:air")))
            h.tick()
            repair.isCompletedExceptionally shouldBe true
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { repair.join() })?.reason shouldBe "stale-building"
            verify(exactly = 0) { h.worldAdapter.repairStorageRacks(any(), any(), any()) }
        }
    }

    test("warehouse maintenance releases its city reservation when world admission throws") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE,
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.WAREHOUSE, 1), control = CivPosition(10, 63, 0))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL),
                buildings = listOf(h.city.buildings.first(), warehouse))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.blueprints.buildingStorageRepair(warehouse) } returns CompletableFuture.completedFuture(
                listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:bookshelf", "minecraft:air")))
            every { h.worldAdapter.repairStorageRacks(any(), any(), any()) } throws CivsRejection("world-closed")
            h.start()
            val repair = h.service.repairBuildingStorage(city.id, warehouse.id)
            h.tick()
            repair.isCompletedExceptionally shouldBe true
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { repair.join() })?.reason shouldBe "world-closed"
            h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true).let { changed ->
                h.saves.size shouldBe 1
                h.commit(); changed.join()
            }
            h.service.city(h.player)!!.settings?.immortalResidents shouldBe true
        }
    }

    test("testing settings require the current owner's permission and only appear after the save") {
        serviceScenario { h ->
            h.start()
            every { h.player.hasPermission("arccivs.testing") } returns false
            h.service.canChangeSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS) shouldBe false
            val denied = h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true)
            h.tick()
            (shouldThrow<CompletionException> { denied.join() }.cause as CivsRejection).reason shouldBe "not-authorized"
            h.saves shouldBe emptyList()
            every { h.player.hasPermission("arccivs.testing") } returns true
            val changed = h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true)
            h.service.city(h.player)!!.settings shouldBe null
            changed.isDone shouldBe false
            h.commit(); changed.join()
            h.service.city(h.player)!!.settings shouldBe CivSettlementSettings(immortalResidents = true)
            val writes = h.saves.size
            h.service.setSettlementSetting(h.player, CivSettlementSetting.IMMORTAL_RESIDENTS, true).join()
            h.saves.size shouldBe writes
            every { h.player.hasPermission("arccivs.chunkload") } returns false
            h.service.canChangeSettlementSetting(h.player, CivSettlementSetting.CHUNK_LOADING) shouldBe false
        }
    }

    test("testing construction issuance journals the exact carried deficit once and stops on permission revocation") {
        mockkConstructor(CivsChunkPermissions::class)
        try {
            every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns true
            serviceScenario { h ->
                val placement = h.incrementalPlacement()
                val order = placement.city.construction!!
                val configured = placement.city.copy(settings = CivSettlementSettings(autoConstructionMaterials = true),
                    construction = order.copy(phase = ConstructionPhase.WAITING_MATERIALS,
                        payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L))))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(configured))
                every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
                every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
                h.start()
                every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns false
                h.service.prepareTestingConstructionSupplies(configured.id).join()
                CivsEquipment.usableCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 0L
                verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
                every { anyConstructed<CivsChunkPermissions>().allowed(any(), any()) } returns true
                val grant = h.service.prepareTestingConstructionSupplies(configured.id)
                h.tick()
                h.service.city(h.player)!!.workers.first().items shouldBe null
                h.commit(); grant.join()
                val carried = h.service.city(h.player)!!.workers.first()
                CivsEquipment.usableCount(carried, "STONE") shouldBe 1L
                h.service.prepareTestingConstructionSupplies(configured.id).join()
                verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            }
        } finally { unmockkConstructor(CivsChunkPermissions::class) }
    }
    test("layout debug exports city and bounded runtime snapshots without native capture or city writes by default") {
        serviceScenario { h ->
            h.start()
            val result = h.service.debugLayout(h.city.id, false)
            val receipt = h.await(result)
            receipt["city"] shouldBe h.city.id
            receipt["source_revision"] shouldBe h.city.revision
            receipt["layout_unchanged"] shouldBe true
            val file = h.plugin.dataFolder.toPath().resolve(receipt["path"].toString())
            val document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).asJsonObject
            document.getAsJsonObject("city")["world"].asString shouldBe h.world.uid.toString()
            document.getAsJsonObject("runtime").keySet().containsAll(listOf("citizens", "worksites", "supply", "coverage")) shouldBe true
            document["surface"].isJsonNull shouldBe true
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any()) }
            h.saves shouldBe emptyList()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.service.debugLayout(UUID.randomUUID(), false).join() })
                ?.reason shouldBe "city-not-found"
        }
    }

    test("explicit layout terrain uses the existing loaded-only height field with the pinned hall") {
        serviceScenario { h ->
            val hall = h.city.buildings.first()
            val surface = CivTerrainSurface(-64, 320, mapOf(civsTerrainKey(0, 0) to CivTerrainSurfaceColumn(63, true)))
            every { h.worldAdapter.autonomousHeightField(h.city, hall, hall) } returns CompletableFuture.completedFuture(surface)
            h.start()
            val receipt = h.await(h.service.debugLayout(h.city.id, true))
            val document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                h.plugin.dataFolder.toPath().resolve(receipt["path"].toString()))).asJsonObject
            document.getAsJsonObject("surface")["loaded_only"].asBoolean shouldBe true
            document.getAsJsonObject("surface")["atomic"].asBoolean shouldBe false
            verify(exactly = 1) { h.worldAdapter.autonomousHeightField(h.city, hall, hall) }
            h.saves shouldBe emptyList()
        }
        serviceScenario { h ->
            val unavailable = h.city.copy(worldId = UUID.randomUUID())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(unavailable))
            h.start()
            CivsDiagnostics.rejection(shouldThrow<CompletionException> { h.service.debugLayout(unavailable.id, true).join() })
                ?.reason shouldBe "terrain-world-not-loaded"
            verify(exactly = 0) { h.worldAdapter.autonomousHeightField(any(), any(), any()) }
            h.saves shouldBe emptyList()
        }
    }

    test("global worksite debug includes the ninth city's cached need beyond the city row sample") {
        serviceScenario { h ->
            val forester = h.city.buildings.last().copy(type = BuildingType.FORESTER,
                schematicId = "civs/minecolonies/nordic/fundamentals/lumberjack1", production = "OAK_LOG")
            val worker = h.city.workers.first().copy(assignment = forester.id, nextDeliveryAt = 1)
            val ninth = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), forester),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL)))
            val earlier = List(8) { h.city.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), workers = emptyList()) }
            val cities = earlier + ninth
            every { h.store.load() } returns CompletableFuture.completedFuture(cities)
            h.start()
            h.paper.performTicks(5) // The only eligible worksite belongs to the ninth city.
            h.service.settlements().map { it.id } shouldBe cities.map { it.id }
            val global = h.service.debugState("worksites", null, null)
            global["total"] shouldBe 1
            global["truncated"] shouldBe false
            val entry = (global["entries"] as List<*>).single() as Map<*, *>
            entry["worker"] shouldBe worker.id.toString()
            (entry["need"] as Map<*, *>)["building"] shouldBe forester.id.toString()
            h.service.debugState("worksites", earlier.first().id, null)["total"] shouldBe 0
            h.service.debugState("worksites", ninth.id, null)["entries"] shouldBe global["entries"]
            h.saves shouldBe emptyList()
        }
    }

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
                verify(exactly = 1) { h.worldAdapter.validateRoadUpkeep(after, upkeep.work) }
                verify(exactly = 1) { anyConstructed<CivsFootTraffic>().registerSurface(after.id, upkeep.work.changes.single().position) }
            }
        } finally { unmockkConstructor(CivsFootTraffic::class) }
    }

    test("useful crop care requires arrival at the plot and commits its cycle only through the journal") {
        serviceScenario { h ->
            val world = CivsWorksiteWorldFixture("service-crop-care").also(h.paper.server::addWorld)
            val anchor = CivPosition(30, 63, 0)
            val farm = h.city.buildings.last().copy(type = BuildingType.FARM, anchor = anchor, level = 1,
                schematicId = "civs/minecolonies/medievaloak/agriculture/horticulture/farmer1",
                bounds = CivBox(CivPosition(26, 63, -22), CivPosition(48, 80, -1)),
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, -3), production = "WHEAT")
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1,
                inventory = null, items = List(CivsEquipment.SLOTS) { if (it == 4) CivResidentItem("IRON_HOE", 1) else null })
            val ready = h.city.copy(worldId = world.uid, buildings = listOf(h.city.buildings.first(), farm),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            for (x in 0..4) for (z in -3..1) world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=3]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = 2 }
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            every { h.citizens.requireStorageArrival(any(), any(), any()) } throws CivsRejection("stale-delivery")
            h.start()
            val runtime = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }.get(h.service) as CivsWorksites
            // Arrival and journal ordering use deterministic planning; pulse budgets have separate coverage.
            CivsWorksites::class.java.getDeclaredField("nanoTime").also { it.isAccessible = true }
                .set(runtime, { 0L })
            repeat(32) { if (runtime.plan(h.service.city(h.player)!!, worker) == null) h.paper.performTicks(5) }
            val work = requireNotNull(runtime.plan(h.service.city(h.player)!!, worker))
            work.status shouldBe "watering"
            work.changes.size shouldBe 2
            work.changes.map { it.before to it.after }.toSet() shouldBe setOf(
                "minecraft:farmland[moisture=3]" to "minecraft:farmland[moisture=7]",
                "minecraft:wheat[age=2]" to "minecraft:wheat[age=3]",
            )
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE)
                .also { it.isAccessible = true }
            fun complete() = deliver.invoke(h.service, ready.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val completion = complete()
            val accepted = requireNotNull(receipt)
            accepted.changes shouldBe emptyList()
            accepted.worldChanges shouldBe work.changes
            accepted.after.workers.single().completedCycles shouldBe 1L
            accepted.after.workers.single().skills shouldBe worker.skills
            accepted.after.workers.single().items shouldBe worker.items
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
            h.saves shouldBe emptyList()
            completion.isDone shouldBe false
            verify(exactly = 2) { h.citizens.requireWorldWorkArrival(ready.id, worker.id, work) }
            verify(exactly = 0) { h.citizens.requireStorageArrival(any(), any(), any()) }
            journal.complete(accepted); h.tick()
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
            h.commit(); completion.join()
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 1L
            complete().isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (fullBag in listOf(false, true)) test("harvest enters only the real producer bag after its journal and rejects fullBag=$fullBag before native effects") {
        serviceScenario { h ->
            val world = CivsWorksiteWorldFixture("service-harvest-bag").also(h.paper.server::addWorld)
            val farm = h.city.buildings.last().copy(type = BuildingType.FARM, anchor = CivPosition(30, 63, 0), level = 1,
                schematicId = "civs/minecolonies/medievaloak/agriculture/horticulture/farmer1",
                bounds = CivBox(CivPosition(26, 63, -22), CivPosition(48, 80, -1)),
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, -3), production = "WHEAT")
            val worker = h.city.workers.first().copy(assignment = farm.id, nextDeliveryAt = 1,
                inventory = null, items = List(CivsEquipment.SLOTS) { slot -> when {
                    slot == 4 -> CivResidentItem("IRON_HOE", 1)
                    fullBag && slot >= CivsEquipment.BAG -> CivResidentItem("STONE", 64)
                    else -> null
                } })
            val ready = h.city.copy(worldId = world.uid, buildings = listOf(h.city.buildings.first(), farm),
                workers = listOf(worker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL))
            for (x in 0..4) for (z in -3..1) world.loadChunk(x, z)
            CivsWorksiteLayouts.field(farm).forEach { soil ->
                world.getBlockAt(soil.x, soil.y, soil.z).blockData = Bukkit.createBlockData("minecraft:farmland[moisture=7]")
                world.getBlockAt(soil.x, soil.y + 1, soil.z).blockData =
                    (Material.WHEAT.createBlockData() as Ageable).also { it.age = it.maximumAge }
            }
            CivsWorksiteLayouts.irrigation(farm).forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.WATER }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val runtime = CivsService::class.java.getDeclaredField("worksites").also { it.isAccessible = true }.get(h.service) as CivsWorksites
            repeat(64) { if (runtime.plan(h.service.city(h.player)!!, worker) == null) h.paper.performTicks(5) }
            val work = requireNotNull(runtime.plan(h.service.city(h.player)!!, worker))
            ((work.outputs["WHEAT"] ?: 0L) > 0L) shouldBe true
            val focus = requireNotNull(work.focus)
            val crop = world.getBlockAt(focus.x, focus.y, focus.z)
            val nativeBefore = crop.blockData.asString
            val deliver = CivsService::class.java.getDeclaredMethod("deliver", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE).also { it.isAccessible = true }
            fun complete() = deliver.invoke(h.service, ready.id, worker.id, farm.id, worker.completedCycles,
                worker.nextDeliveryAt, null, h.time) as CompletableFuture<*>
            val result = complete()
            crop.blockData.asString shouldBe nativeBefore
            h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
            if (fullBag) {
                val failure = shouldThrow<CompletionException> { result.join() }.cause as CivsRejection
                failure.reason shouldBe "storage-full"
                receipt shouldBe null
                h.saves shouldBe emptyList()
                h.service.city(h.player)!!.workers.single().items shouldBe worker.items
            } else {
                val accepted = requireNotNull(receipt)
                accepted.changes shouldBe emptyList()
                accepted.after.inventory shouldBe ready.inventory
                work.outputs.forEach { (material, amount) ->
                    CivsEquipment.plainCount(accepted.after.workers.single(), material) shouldBe amount.toInt()
                    CivsEquipment.plainCount(h.service.city(h.player)!!.workers.single(), material) shouldBe 0
                }
                journal.complete(accepted); h.tick()
                (crop.blockData.asString != nativeBefore) shouldBe true
                h.service.city(h.player)!!.workers.single().completedCycles shouldBe 0L
                h.commit(); result.join()
                work.outputs.forEach { (material, amount) -> CivsEquipment.plainCount(h.service.city(h.player)!!.workers.single(), material) shouldBe amount.toInt() }
                complete().isCompletedExceptionally shouldBe true
                verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            }
        }
    }

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

    test("a replaced same cycle craft rejects both old service callbacks without consuming stock or advancing work") {
        serviceScenario { h ->
            val (workshop, carpenter) = h.prepareCarpentry(mapOf("OAK_LOG" to 1L))
            h.start()
            val old = h.craftJob(carpenter.id)
            val dispatcher = CivsService::class.java.getDeclaredField("supply").let {
                it.isAccessible = true; it.get(h.service) as CivsSupplyDispatcher
            }
            dispatcher.releaseCraft(h.city.id, carpenter.id, old.id)
            val replacement = h.craftJob(carpenter.id)
            (replacement.id != old.id) shouldBe true
            replacement.cycle shouldBe old.cycle
            val before = requireNotNull(h.service.city(h.player))
            val supplies = h.service.prepareResidentSupplies(before.id, carpenter.id, workshop.id, old.id)
            (shouldThrow<CompletionException> { h.await(supplies) }.cause as CivsRejection).reason shouldBe "stale-delivery"
            // Invoke the exact private callback wired into CivsCitizens, without adding a production test seam.
            val delivery = CivsService::class.java.getDeclaredMethod("deliverCraft", UUID::class.java, UUID::class.java,
                UUID::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, UUID::class.java, java.lang.Long.TYPE).let {
                it.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (it.invoke(h.service, before.id, carpenter.id, workshop.id, carpenter.completedCycles,
                    carpenter.nextDeliveryAt, old.id, h.time) as CompletableFuture<Settlement>)
            }
            (shouldThrow<CompletionException> { h.await(delivery) }.cause as CivsRejection).reason shouldBe "stale-delivery"
            h.service.city(h.player) shouldBe before
            h.service.localMaterials(h.player, workshop.id) shouldBe mapOf("OAK_LOG" to 1L)
            h.saves shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
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

    test("remembering a renamed existing member advances the revision without changing membership") {
        serviceScenario { h ->
            val memberId = UUID.randomUUID()
            val ready = h.city.copy(members = setOf(memberId), memberNames = mapOf(memberId.toString() to "OldName"))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            every { h.player.name } returns "Owner"
            h.start()
            val member = org.mockbukkit.mockbukkit.entity.PlayerMock(h.paper.server, "NewName", memberId).also(h.paper.server::addPlayer)
            val updated = h.service.addMember(h.player, member.name)
            h.tick()
            h.saves.single().first.let {
                it.revision shouldBe ready.revision + 1
                it.members shouldBe ready.members
                it.memberNames!![memberId.toString()] shouldBe "NewName"
            }
            h.commit(); updated.join()
            h.service.city(h.player)!!.memberNames!![memberId.toString()] shouldBe "NewName"
        }
    }

    test("courier pickup and unload journal both physical endpoints and persist cargo between them") {
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
            val journals = mutableListOf<Pair<CivInventoryTransaction, CompletableFuture<CivInventoryTransaction>>>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture<CivInventoryTransaction>().also { journals += firstArg<CivInventoryTransaction>() to it }
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val current = h.service.city(h.player)!!
            val plan = h.supplyPlan(current.workers.first().id)
            val pickup = h.service.pickupCargo(current.id, current.workers.first().id, plan.cargo)
            source.getItem(0)!!.amount shouldBe 3
            h.service.city(h.player)!!.workers.first().cargo shouldBe null
            journals.single().let { it.second.complete(it.first) }; h.tick()
            source.getItem(0) shouldBe null
            target.isEmpty shouldBe true
            h.service.city(h.player)!!.workers.first().cargo shouldBe null
            h.commit(); pickup.join()
            h.service.city(h.player)!!.workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
            h.service.pickupCargo(current.id, current.workers.first().id, plan.cargo).join()
            journals.size shouldBe 1
            val dropoff = h.service.dropoffCargo(current.id, current.workers.first().id, plan.cargo, smith.id)
            target.isEmpty shouldBe true
            journals.last().let { it.second.complete(it.first) }; h.tick()
            target.getItem(0)!!.amount shouldBe 3
            h.service.city(h.player)!!.workers.first().cargo shouldBe plan.cargo.copy(inventoryBacked = true)
            h.commit(); dropoff.join()
            val finished = h.service.city(h.player)!!.workers.first()
            finished.cargo shouldBe null
            finished.completedCycles shouldBe 1L
            finished.skills!!["WAREHOUSE"] shouldBe 1L
            h.service.dropoffCargo(current.id, current.workers.first().id, plan.cargo, smith.id).isCompletedExceptionally shouldBe true
            journals.size shouldBe 2
            source.getItem(1)!!.amount shouldBe 2
            target.getItem(0)!!.type shouldBe Material.IRON_INGOT
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
            val factory = CivsService::class.java.getDeclaredMethod("captureSupply", UUID::class.java, Map::class.java)
                .also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val step = factory.invoke(h.service, h.city.id, emptyMap<UUID, CivCourierPlan>()) as () -> CivSupplySnapshot?
            val member = h.paper.addPlayer("SupplyBusyMember")
            val save = h.service.addMember(h.player, member.name)
            save.isDone shouldBe false
            shouldThrow<CivsRejection> { step() }.reason shouldBe "city-busy"
            h.commit(); save.join()
            @Suppress("UNCHECKED_CAST")
            val retry = factory.invoke(h.service, h.city.id, emptyMap<UUID, CivCourierPlan>()) as () -> CivSupplySnapshot?
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
            val publish = CivsService::class.java.getDeclaredMethod("publish", Settlement::class.java).also { it.isAccessible = true }
            (publish.invoke(h.service, changed) as CompletableFuture<*>).join()
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

    test("worker-bag cargo accepts source movement then waits for both journals and moves once through the real courier inventory") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 63, 0))
            val quarry = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY, anchor = CivPosition(20, 64, 0),
                entrance = CivPosition(20, 64, -2), control = CivPosition(20, 63, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
            val producer = h.city.workers[1].copy(assignment = quarry.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { when (it) {
                    4 -> CivResidentItem("IRON_PICKAXE", 1)
                    6 -> CivResidentItem("COAL", 64)
                    7 -> CivResidentItem("COBBLESTONE", 32)
                    8 -> CivResidentItem("BREAD", 4)
                    else -> null
                } })
            val ready = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), warehouse, quarry),
                workers = listOf(h.city.workers.first().copy(assignment = warehouse.id, nextDeliveryAt = 1), producer))
            val meeting = CivPosition(20, 64, 3)
            val moved = CivPosition(25, 64, 3)
            var sourceObservations = 0
            every { h.citizens.courierSupplySources(ready.id, any()) } answers {
                sourceObservations++
                mapOf(producer.id to if (sourceObservations == 1) meeting else moved)
            }
            every { h.citizens.courierSourceCurrent(ready.id, any()) } answers {
                val cargo = secondArg<CivCargo>()
                cargo.sourceWorkerId shouldBe producer.id
                cargo.sourceBuildingId shouldBe quarry.id
                cargo.material shouldBe "COAL"
                cargo.amount shouldBe 64L
                cargo.sourceEntrance shouldBe meeting
                true
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.world.loadChunk(1, 0)
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.world.getBlockAt(20, 63, 0).type = Material.BARREL
            val target = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            val journals = mutableListOf<Pair<CivInventoryTransaction, CompletableFuture<CivInventoryTransaction>>>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                CompletableFuture<CivInventoryTransaction>().also { journals += firstArg<CivInventoryTransaction>() to it }
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val plan = h.supplyPlan(ready.workers.first().id)
            plan.cargo.sourceEntrance shouldBe meeting
            verify(atLeast = 1) { h.citizens.courierSourceCurrent(ready.id, plan.cargo) }
            val rejected = h.service.pickupCargo(ready.id, ready.workers.first().id, plan.cargo)
            journals.single().second.completeExceptionally(CivsRejection("storage-transaction-too-large")); h.tick()
            rejected.isCompletedExceptionally shouldBe true
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.last(), "COAL") shouldBe 64
            h.service.city(h.player)!!.workers.first().cargo shouldBe null
            val pickup = h.service.pickupCargo(ready.id, ready.workers.first().id, plan.cargo)
            val receipt = journals.last().first
            receipt.changes shouldBe emptyList()
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.last(), "COAL") shouldBe 64
            journals.last().second.complete(receipt); h.tick()
            target.isEmpty shouldBe true
            h.service.city(h.player)!!.workers.first().cargo shouldBe null
            h.commit(); pickup.join()
            val carried = h.service.city(h.player)!!
            CivsEquipment.plainCount(carried.workers.first(), "COAL") shouldBe 64
            CivsEquipment.plainCount(carried.workers.last(), "COAL") shouldBe 0
            CivsEquipment.plainCount(carried.workers.last(), "COBBLESTONE") shouldBe 32
            CivsEquipment.plainCount(carried.workers.last(), "BREAD") shouldBe 4
            h.service.pickupCargo(ready.id, ready.workers.first().id, plan.cargo).join()
            journals.size shouldBe 2
            val dropoff = h.service.dropoffCargo(ready.id, ready.workers.first().id, plan.cargo, warehouse.id)
            target.isEmpty shouldBe true
            journals.last().let { it.second.complete(it.first) }; h.tick()
            target.getItem(0)!!.amount shouldBe 64
            h.service.city(h.player)!!.workers.first().cargo!!.id shouldBe plan.cargo.id
            h.commit(); dropoff.join()
            val finished = h.service.city(h.player)!!
            finished.workers.first().cargo shouldBe null
            CivsEquipment.plainCount(finished.workers.first(), "COAL") shouldBe 0
            finished.workers.first().completedCycles shouldBe 1L
            verify(exactly = 2) { h.citizens.requireCargoPickupArrival(ready.id, ready.workers.first().id, plan.cargo) }
        }
    }

    test("a worker-bag pickup rechecks the physical meeting after waiting for another city commit") {
        serviceScenario { h ->
            val warehouse = h.city.buildings.last().copy(type = BuildingType.WAREHOUSE, control = CivPosition(10, 63, 0))
            val quarry = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY, anchor = CivPosition(20, 64, 0),
                entrance = CivPosition(20, 64, -2), control = CivPosition(20, 63, 0),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
            val producer = h.city.workers[1].copy(assignment = quarry.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COAL", 64) else null })
            val ready = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), warehouse, quarry),
                workers = listOf(h.city.workers.first().copy(assignment = warehouse.id, nextDeliveryAt = 1), producer))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(ready))
            h.world.loadChunk(1, 0)
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            h.world.getBlockAt(20, 63, 0).type = Material.BARREL
            every { h.citizens.courierSupplySources(ready.id, any()) } returns mapOf(producer.id to CivPosition(20, 64, 3))
            every { h.citizens.courierSourceCurrent(ready.id, any()) } returns true
            h.start()
            val plan = h.supplyPlan(ready.workers.first().id)
            val member = h.paper.addPlayer("CargoWaitMember")
            val membership = h.service.addMember(h.player, member.name)
            val pickup = h.service.pickupCargo(ready.id, ready.workers.first().id, plan.cargo)
            pickup.isDone shouldBe false
            every { h.citizens.requireCargoPickupArrival(ready.id, ready.workers.first().id, plan.cargo) } throws CivsRejection("route-unreachable")
            h.commit(); membership.join(); h.tick()
            pickup.isCompletedExceptionally shouldBe true
            val current = h.service.city(h.player)!!
            CivsEquipment.plainCount(current.workers.last(), "COAL") shouldBe 64
            current.workers.first().cargo shouldBe null
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("ordinary save leaves menus available and queued assignment uses the committed city") {
        serviceScenario { h ->
            h.start()
            val member = h.paper.addPlayer("QueuedMember")
            val worker = h.city.workers.first()
            val membership = h.service.addMember(h.player, member.name)
            h.service.cityBusy(h.player) shouldBe false
            val assignment = h.service.assign(h.player, worker.id, h.city.buildings.first().id)
            assignment.isDone shouldBe false
            h.saves.size shouldBe 1
            h.commit(); membership.join()
            h.saves.size shouldBe 2
            val next = h.saves.last().first
            (member.uniqueId in next.members) shouldBe true
            next.workers.first().assignment shouldBe h.city.buildings.first().id
            next.revision shouldBe h.saves.first().first.revision + 1
            h.commit(); assignment.join()
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

    test("a queued action is cancelled after logout without a second save") {
        serviceScenario { h ->
            h.start()
            val member = h.paper.addPlayer("LogoutMember")
            val membership = h.service.addMember(h.player, member.name)
            val assignment = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            val residentActions = listOf(h.service.feedWorker(h.player, h.city.workers.first().id),
                h.service.supplyWorker(h.player, h.city.workers.first().id, "BREAD"),
                h.service.returnWorkerItem(h.player, h.city.workers.first().id, "BREAD"))
            every { h.player.isOnline } returns false
            h.commit(); membership.join()
            shouldThrow<CompletionException> { assignment.join() }.cause.let { (it as CivsRejection).reason } shouldBe "player-offline"
            residentActions.forEach { future ->
                shouldThrow<CompletionException> { future.join() }.cause.let { (it as CivsRejection).reason } shouldBe "player-offline"
            }
            h.saves.size shouldBe 1
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

    test("construction supply journals both endpoints before debit and keeps their locks until receipt retirement") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SupplyOwner").also { it.setOp(true) }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val site = h.supplySite(player.uniqueId, 6)
            val inactiveCity = h.store.load().join().single().copy(workers = emptyList())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(inactiveCity))
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 2))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            val journal = CompletableFuture<CivInventoryTransaction>()
            val retirement = CompletableFuture<Boolean>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns retirement
            h.start()
            val supplied = h.service.supplyConstruction(player, site.building.id); h.tick()
            player.inventory.getItem(0)!!.amount shouldBe 10
            barrel.getItem(0)!!.amount shouldBe 2
            h.saves shouldBe emptyList()
            requireNotNull(receipt).playerDebit!!.playerId shouldBe player.uniqueId
            val second = h.service.supplyConstruction(player, site.building.id)
            second.isDone shouldBe false
            journal.complete(receipt); h.tick()
            player.inventory.getItem(0)!!.amount shouldBe 6
            barrel.getItem(0)!!.amount shouldBe 6
            h.saves.size shouldBe 1
            supplied.isDone shouldBe false
            h.commit()
            supplied.isDone shouldBe false
            h.paper.callEvent(org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe true
            retirement.complete(true); h.tick()
            supplied.join() shouldBe CivsSupplyResult(4, 0, false)
            second.join() shouldBe CivsSupplyResult(0, 0, false)
            h.paper.callEvent(org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe false
            val repeated = h.service.supplyConstruction(player, site.building.id); h.tick()
            repeated.join() shouldBe CivsSupplyResult(0, 0, false)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("admin construction grant checks its server permission before reading or changing materials") {
        serviceScenario { h ->
            every { h.player.hasPermission("arccivs.admin") } returns false
            h.start()
            val result = h.service.grantConstructionMaterials(h.player, h.city.buildings.first().id)
            (shouldThrow<CompletionException> { result.join() }.cause as CivsRejection).reason shouldBe "not-authorized"
            verify(exactly = 0) { h.store.loadPlan(any()); h.store.saveInventoryTransaction(any()); h.store.save(any()) }
        }
    }

    test("admin construction grant uses the same journal without debiting the administrator") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SupplyAdmin").also { it.setOp(true) }
            // Unlike Paper, a fresh MockBukkit player has a view with no top inventory.
            (player.openInventory as org.mockbukkit.mockbukkit.inventory.InventoryViewMock)
                .setTopInventory(h.paper.server.createInventory(player, 9))
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val site = h.supplySite(player.uniqueId, 6)
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 2))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers {
                val accepted = firstArg<CivInventoryTransaction>()
                receipt = accepted; CompletableFuture.completedFuture(accepted)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val result = h.service.grantConstructionMaterials(player, site.building.id); h.tick()
            requireNotNull(receipt).playerDebit shouldBe null
            barrel.getItem(0)!!.amount shouldBe 6
            player.inventory.getItem(0)!!.amount shouldBe 10
            h.commit()
            result.join() shouldBe CivsSupplyResult(4, 0, false)
        }
    }

    test("explicit builder hut deposits subtract all builder stores and bags without using site or hall contents") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("HutSupplier")
            player.addAttachment(h.plugin, "arccivs.use", true)
            player.teleport(Location(h.world, 20.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 6)
            val base = h.store.load().join().single()
            val first = base.buildings.last().copy(phase = BuildingPhase.ACTIVE)
            val second = first.copy(id = UUID.randomUUID(), anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, 3),
                bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), control = CivPosition(20, 63, 0))
            val target = second.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                anchor = CivPosition(30, 64, 0), entrance = CivPosition(30, 64, 3), control = CivPosition(30, 63, 0),
                bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)))
            val worker = base.workers.first().copy(assignment = first.id, nextDeliveryAt = 1, items = List(CivsEquipment.SLOTS) {
                if (it == 6) CivResidentItem("OAK_LOG", 2) else null
            })
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 1, 0, ConstructionPhase.PAUSED, Stock(),
                failure = "placement-cancelled", plannedBuilding = target, batches = CivConstructionBatches(1, 16),
                materials = mapOf("OAK_LOG" to 6L), payment = CivConstructionPayment())
            val hall = base.buildings.first().copy(control = CivPosition(0, 63, 0))
            val city = CivsRules.validate(base.copy(buildings = listOf(hall, first, second, target), workers = listOf(worker), construction = order))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.world.loadChunk(1, 0)
            for (x in listOf(0, 20, 30)) h.world.getBlockAt(x, 63, 0).type = Material.BARREL
            fun barrel(x: Int) = (h.world.getBlockAt(x, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel(0).setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 64))
            barrel(10).setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 1))
            barrel(30).setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 64))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            h.start()
            h.service.constructionAvailable(player, target.id)["OAK_LOG"] shouldBe 3L
            val supplied = h.service.supplyConstruction(player, target.id, second.id); h.tick()
            barrel(20).getItem(0)!!.amount shouldBe 3
            barrel(10).getItem(0)!!.amount shouldBe 1
            barrel(0).getItem(0)!!.amount shouldBe 64
            barrel(30).getItem(0)!!.amount shouldBe 64
            player.inventory.getItem(0)!!.amount shouldBe 7
            h.commit(); h.await(supplied) shouldBe CivsSupplyResult(3, 0, false)
            h.await(h.service.supplyConstruction(player, target.id, second.id)) shouldBe CivsSupplyResult(0, 0, false)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 0) { h.store.loadPlan(plan.id) }
        }
    }

    test("creative builder bag grant is durable capacity bounded repeat safe and rejects survival issuance") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("CreativeBuilderSupplier")
            player.addAttachment(h.plugin, "arccivs.use", true)
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val placement = h.incrementalPlacement(phase = ConstructionPhase.PAUSED)
            val base = placement.city
            val worker = base.workers.first().copy(items = List(CivsEquipment.SLOTS) {
                when { it == 6 -> CivResidentItem("STONE", 63); it == 7 || it < 6 -> null; else -> CivResidentItem("DIRT", 64) }
            })
            val city = CivsRules.validate(base.copy(owner = player.uniqueId, memberNames = mapOf(player.uniqueId.toString() to player.name),
                workers = listOf(worker), construction = base.construction!!.copy(materials = mapOf("STONE" to 65L, "OAK_LOG" to 70L))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val journal = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); journal }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            h.start()
            player.gameMode = org.bukkit.GameMode.SURVIVAL
            val denied = h.service.grantBuilderMaterials(player, worker.id)
            (shouldThrow<CompletionException> { h.await(denied) }.cause as CivsRejection).reason shouldBe "not-authorized"
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            player.gameMode = org.bukkit.GameMode.CREATIVE
            val granted = h.service.grantBuilderMaterials(player, worker.id); h.tick()
            requireNotNull(receipt).playerDebit shouldBe null
            requireNotNull(receipt).residentTransfer shouldBe null
            h.service.city(player)!!.workers.single() shouldBe worker
            h.saves shouldBe emptyList()
            journal.complete(receipt); h.tick()
            granted.isDone shouldBe false
            h.commit(); h.await(granted) shouldBe CivsSupplyResult(65, 7, true)
            CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), "STONE") shouldBe 64
            CivsEquipment.plainCount(h.service.city(player)!!.workers.single(), "OAK_LOG") shouldBe 64
            h.await(h.service.grantBuilderMaterials(player, worker.id)) shouldBe CivsSupplyResult(0, 7, true)
            player.inventory.getItem(0) shouldBe org.bukkit.inventory.ItemStack(Material.DIAMOND, 3)
            verify(exactly = 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (prepaid in listOf(false, true)) test("construction supplies use the unpaid remainder and never refill a prepaid order prepaid=$prepaid") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("RemainderOwner").also { it.setOp(true) }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 20)
            val original = h.store.load().join().single()
            val building = plan.building
            val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, 1, ConstructionPhase.PAUSED,
                Stock(), failure = "placement-cancelled", plannedBuilding = building,
                batches = CivConstructionBatches(2, 16), materials = mapOf("OAK_LOG" to 4L),
                payment = if (prepaid) null else CivConstructionPayment())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(original.copy(
                buildings = original.buildings.map { if (it.id == building.id) building else it }, construction = order)))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 1))
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            h.start()
            val result = h.service.supplyConstruction(player, building.id); h.tick()
            if (!prepaid) h.commit()
            result.join() shouldBe CivsSupplyResult(if (prepaid) 0 else 3, 0, false)
            barrel.getItem(0)!!.amount shouldBe if (prepaid) 1 else 4
            player.inventory.getItem(0)!!.amount shouldBe if (prepaid) 10 else 7
            verify(exactly = 0) { h.store.loadPlan(any()) }
            verify(exactly = if (prepaid) 0 else 1) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a player supply receipt loaded after restart waits for login then completes its original transfer once") {
        serviceScenario { h ->
            val player = h.paper.addPlayer("SupplyReturn").also { it.setOp(true) }
            player.teleport(Location(h.world, 10.0, 65.0, 0.0))
            val plan = h.supplySite(player.uniqueId, 4)
            val city = h.store.load().join().single().copy(workers = emptyList())
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val barrel = (h.world.getBlockAt(10, 63, 0).state as org.bukkit.block.Barrel).inventory
            player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.OAK_LOG, 10))
            val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
                city.copy(revision = city.revision + 1), listOf(CivInventorySlotChange(requireNotNull(plan.building.control),
                    "BARREL", 0, null, CivPlainItem("OAK_LOG", 4))),
                CivPlayerDebit(player.uniqueId, listOf(CivPlayerSlotChange(0, CivPlainItem("OAK_LOG", 10), CivPlainItem("OAK_LOG", 6)))))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(receipt))
            every { h.store.deleteInventoryTransaction(receipt.id) } returns CompletableFuture.completedFuture(true)
            player.disconnect()
            h.start(); h.paper.performTicks(20)
            h.saves shouldBe emptyList()
            barrel.isEmpty shouldBe true
            player.reconnect(); h.paper.performTicks(20)
            h.saves.size shouldBe 1
            player.inventory.getItem(0)!!.amount shouldBe 6
            barrel.getItem(0)!!.amount shouldBe 4
            h.commit(); h.paper.performTicks(20)
            h.service.city(player)!!.revision shouldBe receipt.after.revision
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(receipt.id) }
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

    test("the first ready row waits for carried materials without remote debit or partial journal or paste") {
        val storage = mockk<CivsStorage>(relaxed = true)
        every { storage.prepareConstructionDebitBackground(any(), any(), any()) } returns null
        every { storage.prepareDebit(any(), any()) } throws AssertionError("Construction must use its carried bag")
        serviceScenario(storageFactory = { storage }) { h ->
            h.incrementalPlacement()
            h.start()
            verify(exactly = 0) { storage.prepareConstructionDebitBackground(any(), any(), any()) }
            verify(exactly = 1) { storage.unlock(any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            h.commit()
            h.time += 5_000; h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            h.saves.size shouldBe 1
            verify(exactly = 0) { storage.prepareConstructionDebitBackground(any(), any(), any()) }
            verify(exactly = 0) { storage.prepareDebit(any(), any()); h.store.saveInventoryTransaction(any()) }
        }
    }

    test("autonomous pending control installs for an ordinary visitor while its creator is offline") {
        serviceScenario { h ->
            val viewer = h.paper.addPlayer("VillageVisitor")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            val point = CivPosition(1, 65, 1)
            val hall = h.city.buildings.first().copy(control = point)
            val receipt = CivControlPlacement(hall.id, point, "minecraft:air")
            val village = h.city.copy(autonomy = CivAutonomy(), workers = emptyList(),
                buildings = listOf(hall) + h.city.buildings.drop(1), pendingControls = listOf(receipt))
            village.canManage(viewer.uniqueId) shouldBe false
            h.paper.server.getPlayer(village.owner) shouldBe null
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start(); h.paper.performTicks(20)
            h.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.BARREL
            h.saves.single().first.pendingControls shouldBe emptyList()
            h.commit()
            h.service.settlements().single().pendingControls shouldBe emptyList()
        }
    }

    for (alreadyPlaced in listOf(false, true)) test("pending control restart reconciles alreadyPlaced=$alreadyPlaced without clearing inventory or repeating a completed grant") {
        serviceScenario { h ->
            val viewer = h.paper.addPlayer("ControlOwner")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            val point = CivPosition(1, 65, 1)
            val hall = h.city.buildings.first().copy(control = point)
            val receipt = CivControlPlacement(hall.id, point, "minecraft:air")
            val city = h.city.copy(stock = Stock(), workers = emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                members = setOf(viewer.uniqueId), memberNames = mapOf(viewer.uniqueId.toString() to viewer.name), buildings = listOf(hall) + h.city.buildings.drop(1), pendingControls = listOf(receipt))
            val block = h.world.getBlockAt(point.x, point.y, point.z)
            if (alreadyPlaced) {
                block.type = Material.BARREL
                (block.state as org.bukkit.block.Barrel).inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            block.type shouldBe Material.BARREL
            if (alreadyPlaced) (block.state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            h.saves.single().first.pendingControls shouldBe emptyList()
            h.commit()
            block.type = Material.AIR // Simulate an external world edit, outside the normal protected break flow.
            h.paper.performTicks(60)
            block.type shouldBe Material.AIR
            h.saves.size shouldBe 1
        }
    }

    test("starting an unstaffed site rejects before loading or capturing its design") {
        serviceScenario { h ->
            val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE,
                control = CivPosition(10, 63, 0))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(
                buildings = listOf(h.city.buildings.first(), hut))))
            h.start()
            val result = h.service.startBuilding(h.player, hut.id)
            result.isCompletedExceptionally shouldBe true
            (shouldThrow<CompletionException> { result.join() }.cause as CivsRejection).reason shouldBe "builder-required"
            verify(exactly = 0) {
                h.store.loadPlan(any()); h.store.loadSchematic(any()); h.blueprints.recover(any())
                h.worldAdapter.capture(any(), any(), any(), any(), any(), any())
            }
        }
    }

    test("a builder may start its site with no resources and a separately journalled free approach") {
        serviceScenario { h ->
            val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE,
                control = CivPosition(10, 63, 0), entrance = CivPosition(10, 64, 3))
            h.world.getBlockAt(10, 63, 0).type = Material.BARREL
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), hut), workers = h.city.workers.mapIndexed { index, worker ->
                    if (index == 0) worker.copy(assignment = hut.id, nextDeliveryAt = h.time + 10_000) else worker
                })
            val plan = CivPlacementPlan(hut.id, city.id, city.worldId, hut.copy(phase = BuildingPhase.CONSTRUCTING),
                listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:air", "minecraft:stone")), "a".repeat(64))
            val snapshot = mockk<ArcSchematicSnapshot>()
            every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(hut.id) } returns CompletableFuture.completedFuture(plan)
            every { h.store.loadSchematic(hut.id) } returns CompletableFuture.completedFuture(byteArrayOf(1))
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(plan.copy(id = UUID.randomUUID()))
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            h.start()
            val started = h.service.startBuilding(h.player, hut.id); h.tick()
            h.saves.single().first.construction!!.let {
                it.payment shouldBe CivConstructionPayment()
                it.materials shouldBe mapOf("STONE" to 1L)
                it.batches!!.size shouldBe 16
            }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.saves.single().first.pendingControls!!.single().approach shouldBe listOf(
                CivPlanBlock(CivPosition(10, 63, 3), "minecraft:air", "minecraft:dirt"))
            h.world.getBlockAt(10, 63, 3).type shouldBe Material.AIR
            h.commit(); started.isDone shouldBe false
            h.world.getBlockAt(10, 63, 3).type shouldBe Material.DIRT
            h.commit(); started.join() shouldBe Unit
            h.service.city(h.player)!!.inventory shouldBe city.inventory
        }
    }

    test("site approach is protected and journalled before free changes and never regenerated after retirement") {
        serviceScenario(empty = true) { h ->
            val viewer = h.paper.addPlayer("ApproachOwner")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            every { h.player.uniqueId } returns viewer.uniqueId
            every { h.player.name } returns viewer.name
            val blueprint = h.sitePreview()
            val entrance = blueprint.building.entrance
            val support = h.world.getBlockAt(entrance.x, entrance.y - 1, entrance.z)
            val feet = h.world.getBlockAt(entrance.x, entrance.y, entrance.z).also { it.type = Material.OAK_PLANKS }
            val head = h.world.getBlockAt(entrance.x, entrance.y + 1, entrance.z).also { it.type = Material.GLASS }
            h.start()
            val preview = h.prepareFounding("Approach town", "nordic"); h.tick(); preview.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            val receipt = h.saves.single().first.pendingControls!!.single()
            receipt.approach!!.map { it.after } shouldBe listOf("minecraft:dirt", "minecraft:air", "minecraft:air")
            CivPosition(support.x, support.y, support.z) shouldBe receipt.approach.first().position
            support.type shouldBe Material.AIR
            feet.type shouldBe Material.OAK_PLANKS
            head.type shouldBe Material.GLASS
            h.commit()
            support.type shouldBe Material.DIRT
            feet.type shouldBe Material.AIR
            head.type shouldBe Material.AIR
            val committed = h.service.city(h.player)!!
            committed.pendingControls shouldBe listOf(receipt)
            h.saves.size shouldBe 2
            h.saves.last().second.isDone shouldBe false
            CivPosition(support.x, support.y, support.z) shouldBe receipt.approach.first().position
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.isProtected(h.world.uid, support.x, support.y, support.z) shouldBe true
            viewer.teleport(Location(h.world, 1_000.0, 65.0, 0.0)) // Starter-item materialization needs an actual nearby viewer.
            h.commit(); confirmed.join()
            protection.isProtected(h.world.uid, support.x, support.y, support.z) shouldBe false
            h.service.city(h.player)!!.pendingControls shouldBe emptyList()
            support.type = Material.AIR
            feet.type = Material.STONE
            h.paper.performTicks(60)
            support.type shouldBe Material.AIR
            feet.type shouldBe Material.STONE
            h.saves.size shouldBe 2
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    for (applied in 0..3) test("restart resumes $applied applied approach cells without replacing the existing barrel contents") {
        serviceScenario { h ->
            val viewer = h.paper.addPlayer("ApproachRecovery")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            val entrance = CivPosition(0, 64, 5)
            val point = CivPosition(1, 65, 1)
            val hall = h.city.buildings.first().copy(control = point, entrance = entrance)
            val cells = listOf(CivPlanBlock(entrance.copy(y = 63), "minecraft:air", "minecraft:dirt"),
                CivPlanBlock(entrance, "minecraft:oak_planks", "minecraft:air"),
                CivPlanBlock(entrance.copy(y = 65), "minecraft:glass", "minecraft:air"))
            cells.forEachIndexed { index, cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
                .setBlockData(org.bukkit.Bukkit.createBlockData(if (index < applied) cell.after else cell.before), false) }
            val block = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            val barrel = (block.state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val city = h.city.copy(stock = Stock(), workers = emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(hall) + h.city.buildings.drop(1), members = setOf(viewer.uniqueId),
                memberNames = mapOf(viewer.uniqueId.toString() to viewer.name),
                pendingControls = listOf(CivControlPlacement(hall.id, point, "minecraft:air", cells)))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            cells.forEach { cell -> h.world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.after }
            barrel.getItem(0)!!.amount shouldBe 7
            h.saves.single().first.pendingControls shouldBe emptyList()
            h.commit(); h.paper.performTicks(40)
            h.saves.size shouldBe 1
        }
    }

    test("approach drift after journal commit rejects all writes including the new barrel") {
        serviceScenario(empty = true) { h ->
            val blueprint = h.sitePreview()
            val entrance = blueprint.building.entrance
            val feet = h.world.getBlockAt(entrance.x, entrance.y, entrance.z).also { it.type = Material.OAK_PLANKS }
            h.start()
            val preview = h.prepareFounding("Changed approach", "nordic"); h.tick(); preview.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            feet.type = Material.BEDROCK
            h.commit()
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe "world-changed"
            val control = blueprint.building.control!!
            h.world.getBlockAt(control.x, control.y, control.z).type shouldBe Material.AIR
            h.world.getBlockAt(entrance.x, entrance.y - 1, entrance.z).type shouldBe Material.AIR
            feet.type shouldBe Material.BEDROCK
            h.service.city(h.player)!!.pendingControls!!.size shouldBe 1
            h.saves.size shouldBe 1
        }
    }

    for (obstacle in listOf("bedrock", "inventory", "lands")) test("$obstacle blocks approach preparation without native changes or a city journal") {
        serviceScenario(empty = true) { h ->
            val blueprint = h.sitePreview()
            val entrance = blueprint.building.entrance
            val feet = h.world.getBlockAt(entrance.x, entrance.y, entrance.z)
            feet.type = when (obstacle) { "bedrock" -> Material.BEDROCK; "inventory" -> Material.CHEST; else -> Material.STONE }
            val chest = (feet.state as? org.bukkit.block.Chest)?.blockInventory
            chest?.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            if (obstacle == "lands") every { h.worldAdapter.canModify(any(), feet, Material.AIR) } returns false
            h.start()
            val preview = h.prepareFounding("Protected approach", "nordic"); h.tick(); preview.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe
                if (obstacle == "lands") "lands-denied" else "protected-terrain"
            h.saves shouldBe emptyList()
            h.world.getBlockAt(entrance.x, entrance.y - 1, entrance.z).type shouldBe Material.AIR
            if (chest != null) chest.getItem(0)!!.amount shouldBe 3
        }
    }

    test("approach preparation keeps a solid support barrel and never clears a neighbouring building") {
        serviceScenario { h ->
            val entrance = CivPosition(0, 64, 10)
            val neighbour = h.city.buildings.last().copy(bounds = CivBox(entrance, entrance.copy(y = 65)),
                anchor = entrance, entrance = entrance.copy(z = 11))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(
                buildings = listOf(h.city.buildings.first(), neighbour))))
            val base = h.previewBlueprint(CivPosition(0, 63, 10))
            h.sitePreview(base.copy(building = base.building.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
            val support = h.world.getBlockAt(0, 63, 10).also { it.type = Material.BARREL }
            val inventory = (support.state as org.bukkit.block.Barrel).inventory
            inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 4))
            h.world.getBlockAt(0, 64, 10).type = Material.OAK_PLANKS
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(0, 63, 10))); h.tick(); selected.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe "building-overlap"
            h.saves shouldBe emptyList()
            support.type shouldBe Material.BARREL
            inventory.getItem(0)!!.amount shouldBe 4
            h.world.getBlockAt(0, 64, 10).type shouldBe Material.OAK_PLANKS
        }
    }

    test("automatic builder supply diagnostics distinguish unchanged requests from committed quantities") {
        val results = mutableListOf<Map<String, Any?>>()
        mockkObject(CivsDiagnostics)
        try {
            every { CivsDiagnostics.event(any(), *anyVararg()) } answers {
                if (firstArg<String>() == "resident_supplies_result")
                    results += secondArg<Array<Pair<String, Any?>>>().toMap()
                Unit
            }
            serviceScenario { h ->
                val placement = h.incrementalPlacement()
                every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
                every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
                h.start(); h.commit()
                val worker = h.service.city(h.player)!!.workers.first()
                repeat(2) {
                    val pickup = h.service.prepareResidentSupplies(placement.city.id, worker.id, worker.assignment!!)
                    h.tick(); pickup.join()
                }
                results.size shouldBe 1 // An unchanged empty hut is one outcome, not a per-pulse message.
                results.single()["outcome"] shouldBe "no-op"
                results.single()["source"] shouldBe worker.assignment
                results.single()["order"] shouldBe placement.city.construction!!.id
                results.single()["batch"] shouldBe 0
                results.single()["required"] shouldBe mapOf("STONE" to 1L)
                results.single()["missing_after"] shouldBe mapOf("STONE" to 1L)
                results.single()["amount"] shouldBe 0L
                val barrel = (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
                barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, 1))
                val pickup = h.service.prepareResidentSupplies(placement.city.id, worker.id, worker.assignment!!)
                h.tick()
                results.size shouldBe 1 // A prepared transfer is not a committed pickup.
                h.commit(); pickup.join()
                results.size shouldBe 2
                results.last()["outcome"] shouldBe "committed"
                results.last()["picked"] shouldBe mapOf("STONE" to 1L)
                results.last()["returned"] shouldBe emptyMap<String, Long>()
                results.last()["carried_before"] shouldBe emptyMap<String, Long>()
                results.last()["carried_after"] shouldBe mapOf("STONE" to 1L)
                results.last()["missing_after"] shouldBe emptyMap<String, Long>()
                results.last()["amount"] shouldBe 1L
            }
        } finally { unmockkObject(CivsDiagnostics) }
    }

    test("missing row waits without debit and replenishment resumes only after the paid receipt commits") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement()
            var receipt: CivInventoryTransaction? = null
            val durable = CompletableFuture<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                val tx = firstArg<CivInventoryTransaction>()
                if (tx.after.construction!!.phase == ConstructionPhase.PLACING) { receipt = tx; durable }
                else CompletableFuture.completedFuture(tx)
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start(); h.commit()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.blueprints.paste(any(), any(), any(), any(), any()) }
            val barrel = (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, 1))
            val worker = h.service.city(h.player)!!.workers.first()
            val pickup = h.service.prepareResidentSupplies(placement.city.id, worker.id, worker.assignment!!)
            h.tick(); h.commit(); pickup.join()
            barrel.getItem(0) shouldBe null
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 1
            h.time += 5_000; h.paper.performTicks(20)
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 1
            receipt!!.after.construction!!.phase shouldBe ConstructionPhase.PLACING
            receipt!!.after.construction!!.payment!!.reservedBatch shouldBe 0
            durable.complete(receipt!!); h.tick()
            barrel.getItem(0) shouldBe null
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit(); h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.materials shouldBe mapOf("OAK_PLANKS" to 1L)
            verify(exactly = 2) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            CivsEquipment.plainCount(h.service.city(h.player)!!.workers.first(), "STONE") shouldBe 0
            placement.pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction!!.workDone shouldBe 1
            h.service.city(h.player)!!.construction!!.payment shouldBe CivConstructionPayment()
        }
    }

    for (chunkFailure in listOf(
        ArcSchematicException(ArcSchematicFailure.WORLD_UNAVAILABLE, "Schematic target chunk unloaded during mutation"),
        CivsRejection("chunks-unloaded"),
    )) test("unloaded construction chunks wait and resume the paid row without another debit ${chunkFailure.javaClass.simpleName}") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true, stock = 7)
            h.start(); h.commit()
            placement.pasted.completeExceptionally(chunkFailure); h.tick()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            h.saves.size shouldBe 1
            val retryResult = CompletableFuture<ArcSchematicMutationResult>()
            val retry = mockk<ArcSchematicOperation>(relaxed = true)
            every { retry.completion } returns retryResult
            every { h.blueprints.paste(any(), any(), any(), any(), any()) } returns retry
            h.time += 4_999; h.paper.performTicks(20)
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.time++; h.paper.performTicks(20)
            verify(exactly = 2) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            retryResult.complete(ArcSchematicMutationResult(1, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction!!.workDone shouldBe 1
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a legacy generic placement failure gets one guarded retry after its chunk loads") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(phase = ConstructionPhase.PAUSED, paid = true, stock = 7)
            val recovering = placement.city.copy(construction = placement.city.construction!!.copy(failure = "placement-failed"))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(recovering))
            h.start(); h.paper.performTicks(20)
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
            h.commit(); h.paper.performTicks(20); h.commit()
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            placement.pasted.completeExceptionally(IllegalStateException("permanent native error")); h.tick(); h.commit()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PAUSED
            h.time += 10_000; h.paper.performTicks(40)
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("a paid row survives placement failure and retry without a second inventory charge") {
        serviceScenario { h ->
            h.incrementalPlacement(phase = ConstructionPhase.PAUSED, paid = true, stock = 7)
            h.start()
            val city = h.service.city(h.player)!!
            val retried = h.service.retryConstruction(h.player, city.id); h.tick(); h.commit(); retried.join()
            h.paper.performTicks(20)
            // Retry preserves the paid receipt, but placement still waits for its durable phase transition.
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.saves.filter { !it.second.isDone }.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.commit()
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 7
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("builder site loads once off-thread and reload retires an unfinished geometry request") {
        serviceScenario { h ->
            val city = h.incrementalPlacement(phase = ConstructionPhase.CONSTRUCTING).city
            val order = requireNotNull(city.construction)
            val building = requireNotNull(order.plannedBuilding)
            val cell = CivPlanBlock(CivPosition(13, 63, 0), "minecraft:dirt", "minecraft:air")
            val plan = CivPlacementPlan(order.id, city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone")),
                terrain = CivTerraformPlan(CivBox(cell.position, cell.position), listOf(cell), emptyList(), 63, 1, 0))
            val pending = CompletableFuture<CivPlacementPlan?>()
            every { h.store.loadPlan(order.id) } returns pending
            val lookup = CivsService::class.java.getDeclaredMethod("constructionSite", UUID::class.java, UUID::class.java)
                .also { it.isAccessible = true }
            fun site() = lookup.invoke(h.service, city.id, order.id) as CivConstructionSite?
            h.start()
            site() shouldBe null
            site() shouldBe null
            verify(exactly = 1) { h.store.loadPlan(order.id) }
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            h.start()
            val current = requireNotNull(site())
            current.changedColumns shouldBe setOf(civsTerrainKey(cell.position.x, cell.position.z))
            pending.complete(plan.copy(terrain = null)); h.tick()
            site() shouldBe current
            verify(exactly = 2) { h.store.loadPlan(order.id) }
            h.service.close()
            site() shouldBe null
            verify(exactly = 2) { h.store.loadPlan(order.id) }
        }
    }

    test("paced paste waits for its live builder and accepted feedback is fenced and cannot fail a paid row") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val orderId = placement.city.construction!!.id
            var accepted: java.util.function.Consumer<ArcSchematicBlock>? = null
            var ready: java.util.function.BooleanSupplier? = null
            every { h.blueprints.paste(any(), any(), any(), any(), any()) } answers {
                accepted = arg(3); ready = arg(4); placement.operation
            }
            every { h.citizens.constructionReady(placement.city.id, orderId) } returns false
            h.start()
            accepted shouldBe null
            h.commit()
            requireNotNull(ready).asBoolean shouldBe false
            every { h.citizens.constructionReady(placement.city.id, orderId) } returns true
            ready!!.asBoolean shouldBe true
            val cell = ArcSchematicBlock(ArcSchematicPoint(15, 64, 0), "minecraft:stone", false, false)
            every { h.worldAdapter.constructionFeedback(h.world.uid, cell) } throws IllegalStateException("cosmetic failure")
            accepted!!.accept(cell)
            verify(exactly = 1) { h.citizens.constructionPlaced(placement.city.id, orderId, CivPosition(15, 64, 0), Material.STONE) }
            placement.pasted.isDone shouldBe false
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            h.service.close()
            ready!!.asBoolean shouldBe false
            accepted!!.accept(cell)
            verify(exactly = 1) { h.worldAdapter.constructionFeedback(h.world.uid, cell) }
        }
    }

    test("an unfunded waiting city does not starve the next city's funded row") {
        serviceScenario { h ->
            val base = h.incrementalPlacement().city
            val order = base.construction!!
            val first = CivsRules.awaitMaterials(base, base.owner, order.id, 0, mapOf("STONE" to 1L))
            fun shiftedPoint(point: CivPosition) = point.copy(x = point.x + 256)
            fun shiftedBuilding(building: CivBuilding) = building.copy(anchor = shiftedPoint(building.anchor), entrance = shiftedPoint(building.entrance),
                control = building.control?.let(::shiftedPoint), bounds = CivBox(shiftedPoint(building.bounds.min), shiftedPoint(building.bounds.max)))
            val secondOrder = first.construction!!.copy(id = UUID.randomUUID(), plannedBuilding = shiftedBuilding(order.plannedBuilding!!))
            val second = CivsRules.validate(first.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), center = shiftedPoint(first.center),
                buildings = first.buildings.map(::shiftedBuilding), construction = secondOrder,
                workers = first.workers.mapIndexed { index, worker -> if (index == 0)
                    CivsEquipment.collectSupplies(worker, mapOf("STONE" to 1L)) else worker }))
            h.world.loadChunk(16, 0); h.world.loadChunk(17, 0)
            h.world.getBlockAt(276, 63, 0).type = Material.BARREL
            val barrel = (h.world.getBlockAt(276, 63, 0).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, 1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(first, second))
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val secondPlan = requireNotNull(h.store.loadPlan(order.id).join()).let { plan -> plan.copy(id = secondOrder.id,
                cityId = second.id, building = requireNotNull(secondOrder.plannedBuilding),
                blocks = plan.blocks.map { it.copy(position = shiftedPoint(it.position)) }) }
            every { h.store.loadPlan(secondOrder.id) } returns CompletableFuture.completedFuture(secondPlan)
            every { h.blueprints.recover(secondPlan) } returns CompletableFuture.completedFuture(mockk(relaxed = true))
            every { h.worldAdapter.revalidate(any(), second.owner) } returns CompletableFuture.completedFuture(Unit)
            h.start()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            h.time += 5_000; h.paper.performTicks(20)
            h.saves.single().first.id shouldBe second.id
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            barrel.getItem(0)!!.amount shouldBe 1
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
        }
    }

    test("a late paid receipt after close leaves native inventory untouched for restart recovery") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(stock = 1)
            val supplied = placement.city.copy(workers = placement.city.workers.mapIndexed { index, worker ->
                if (index == 0) CivsEquipment.collectSupplies(worker, mapOf("STONE" to 1L)) else worker })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(supplied))
            val durable = CompletableFuture<CivInventoryTransaction>()
            var receipt: CivInventoryTransaction? = null
            every { h.store.saveInventoryTransaction(any()) } answers { receipt = firstArg(); durable }
            h.start()
            receipt!!.after.construction!!.payment!!.reservedBatch shouldBe 0
            h.service.close()
            durable.complete(receipt!!); h.tick()
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0)!!.amount shouldBe 1
            verify(exactly = 0) { h.store.save(any()); h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    for (nativeApplied in listOf(false, true)) test("recovered batch receipt nativeApplied=$nativeApplied reconciles the debit once then resumes the same row") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(stock = if (nativeApplied) 0 else 1)
            val before = placement.city
            val order = before.construction!!
            // Exact pre-upgrade receipt: these materials were paid directly from the hut, before carried batches.
            val after = before.copy(revision = before.revision + 1, construction = order.copy(
                phase = ConstructionPhase.PLACING, materials = emptyMap(), payment = CivConstructionPayment(0)))
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(CivPosition(20, 63, 0), "BARREL", 0, CivPlainItem("STONE", 1), null)))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            every { h.store.deleteInventoryTransaction(tx.id) } returns CompletableFuture.completedFuture(true)
            h.start(); h.paper.performTicks(20)
            (h.world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory.getItem(0) shouldBe null
            h.commit(); h.paper.performTicks(20)
            h.service.city(h.player)!!.construction!!.payment!!.reservedBatch shouldBe 0
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
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
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveInventoryTransaction(any()) } returns CompletableFuture.failedFuture(CivsRejection("storage-transaction-too-large"))
            h.start()
            val rejected = h.service.supplyWorker(h.player, city.workers.first().id, "BREAD"); h.tick()
            (shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection).reason shouldBe "storage-transaction-too-large"
            h.service.cityBusy(h.player) shouldBe false
            h.service.ready() shouldBe true
            barrel.getItem(0)!!.amount shouldBe 24
            verify(exactly = 0) { h.store.save(any()); h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("direct legacy upgrade reserves the authored control before capture without requiring a player barrel") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().let { it.copy(control = it.anchor) }
            val target = CivPosition(1, 65, 1)
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(hall) + h.city.buildings.drop(1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val upgraded = CivBlueprint(hall.copy(level = 3, control = target), h.world.uid,
                mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(1))
            every { h.blueprints.controlPosition(hall) } returns CompletableFuture.completedFuture(target)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<CivPosition>(5) shouldBe hall.anchor
                arg<CivPosition>(8) shouldBe hall.entrance
                CompletableFuture.completedFuture(if (arg<Int>(2) == 3) upgraded else upgraded.copy(building = hall.copy(control = target)))
            }
            h.start()
            val confirmation = h.service.upgradeBuilding(h.player, hall.id); h.tick()
            verify(exactly = 1) { h.blueprints.prepareUpgrade(hall, 3, h.world.uid) }
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            confirmation.isDone shouldBe false
            h.saves.single().first.pendingControls!!.single().position shouldBe target
            h.world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.AIR
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()); h.store.saveInventoryTransaction(any()) }
            h.commit()
            h.world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.BARREL
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.failedFuture(CivsRejection("outside-city"))
            h.commit()
            (shouldThrow<CompletionException> { confirmation.join() }.cause as CivsRejection).reason shouldBe "outside-city"
            h.service.city(h.player)!!.building(hall.id)!!.control shouldBe target
        }
    }

    test("clicking a barrel and ground uses the same placement point while preserving the barrel contents") {
        serviceScenario(empty = true) { h ->
            val point = CivPosition(0, 63, 0)
            val barrel = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
            val inventory = (barrel.state as org.bukkit.block.Barrel).inventory
            inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                arg<CivPosition>(5) shouldBe point
                CompletableFuture.completedFuture(h.previewBlueprint(point))
            }
            h.start()
            val prepared = h.prepareFounding("Exact barrel", "caledonia"); h.tick(); prepared.join()
            h.service.preview(h.player)!!.let {
                it.anchor shouldBe point
                it.entrance shouldBe point.copy(y = point.y + 1)
                it.control shouldBe CivPosition(0, 64, -5)
            }
            inventory.getItem(0)!!.amount shouldBe 3
            h.service.cancelPreview(h.player)
            barrel.type = Material.GRASS_BLOCK
            val onGround = h.prepareFounding("Same ground", "caledonia"); h.tick(); onGround.join()
            h.service.preview(h.player)!!.anchor shouldBe point
            h.service.preview(h.player)!!.control shouldBe CivPosition(0, 64, -5)
            verify(exactly = 0) { h.store.save(any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("legacy control rebinding commits first and preserves both barrel inventories and shared supplies") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().let { it.copy(control = it.anchor) }
            val target = CivPosition(1, 65, 1)
            val oldBlock = h.world.getBlockAt(hall.anchor.x, hall.anchor.y, hall.anchor.z).also { it.type = Material.BARREL }
            val oldInventory = (oldBlock.state as org.bukkit.block.Barrel).inventory
            oldInventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val newBlock = h.world.getBlockAt(target.x, target.y, target.z).also { it.type = Material.BARREL }
            val newInventory = (newBlock.state as org.bukkit.block.Barrel).inventory
            newInventory.setItem(0, org.bukkit.inventory.ItemStack(Material.EMERALD, 5))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(12, 7, 3)),
                buildings = listOf(hall) + h.city.buildings.drop(1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.worldAdapter.canModify(any(), any(), any()) } returns true
            every { h.blueprints.controlPosition(hall) } returns CompletableFuture.completedFuture(target)
            h.start()
            val stale = h.service.relocateControl(h.player, hall.id, target.copy(x = target.x + 1)); h.tick()
            (shouldThrow<CompletionException> { stale.join() }.cause as CivsRejection).reason shouldBe "control-location-changed"
            h.saves.size shouldBe 0
            val relocating = h.service.relocateControl(h.player, hall.id, target); h.tick()
            relocating.isDone shouldBe false
            h.service.city(h.player)!!.building(hall.id)!!.control shouldBe hall.anchor
            h.saves.single().first.inventory!!.containers shouldBe listOf(hall.anchor)
            h.commit(); relocating.isDone shouldBe false
            h.commit(); relocating.join() shouldBe Unit
            h.service.city(h.player)!!.building(hall.id)!!.control shouldBe target
            h.service.city(h.player)!!.inventory!!.materials shouldBe city.inventory!!.materials
            oldBlock.type shouldBe Material.BARREL
            oldInventory.getItem(0)!!.amount shouldBe 7
            newInventory.getItem(0)!!.amount shouldBe 5
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()); h.store.savePlan(any()) }
        }
    }

    test("legacy control relocation creates its free marker barrel only after the durable reservation") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().let { it.copy(control = it.anchor) }
            val target = CivPosition(1, 65, 1)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), buildings = listOf(hall) + h.city.buildings.drop(1))))
            every { h.blueprints.controlPosition(hall) } returns CompletableFuture.completedFuture(target)
            h.start()
            val relocating = h.service.relocateControl(h.player, hall.id, target); h.tick()
            h.world.getBlockAt(1, 65, 1).type shouldBe Material.AIR
            h.saves.single().first.pendingControls!!.single() shouldBe CivControlPlacement(hall.id, target, "minecraft:air")
            h.commit()
            h.world.getBlockAt(1, 65, 1).type shouldBe Material.BARREL
            relocating.isDone shouldBe false
            h.commit(); relocating.join() shouldBe Unit
            h.service.city(h.player)!!.pendingControls shouldBe emptyList()
        }
    }

    test("control relocation cannot rewrite a saved legacy site plan") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.SITE)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(buildings = listOf(hall))))
            h.start()
            val rejection = shouldThrow<CompletionException> { h.service.relocateControl(h.player, hall.id, hall.anchor).join() }.cause as CivsRejection
            rejection.reason shouldBe "control-relocation-site"
            verify(exactly = 0) { h.store.save(any()); h.store.savePlan(any()) }
        }
    }

    test("new town sites create a free authored barrel after commit and retain it as a protected control") {
        serviceScenario(empty = true) { h ->
            h.start()
            val blueprint = h.previewBlueprint()
            every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            val prepared = h.prepareFounding("Barrel town", "nordic"); h.tick(); prepared.join()
            val point = blueprint.building.control!!
            val block = h.world.getBlockAt(point.x, point.y, point.z)
            val capture = CompletableFuture<CivPlacementPlan>()
            var cityId: UUID? = null
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } answers { cityId = firstArg(); capture }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val confirming = h.service.confirmPreview(h.player)
            capture.complete(CivPlacementPlan(UUID.randomUUID(), cityId!!, h.world.uid, blueprint.building, emptyList()))
            h.tick()
            block.type shouldBe Material.AIR
            h.service.city(h.player) shouldBe null
            h.saves.single().first.pendingControls!!.single().position shouldBe point
            h.commit()
            block.type shouldBe Material.BARREL
            h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!.isProtected(h.world.uid, point.x, point.y, point.z) shouldBe true
            h.commit(); confirming.join() shouldBe Unit
            h.service.city(h.player)!!.pendingControls shouldBe emptyList()
            verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        }
    }

    test("an obstructed control after site commit completes with an error and keeps its recoverable receipt") {
        serviceScenario(empty = true) { h ->
            h.start()
            val blueprint = h.previewBlueprint()
            every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } answers {
                CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), h.world.uid, blueprint.building, emptyList()))
            }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val prepared = h.prepareFounding("Barrel town", "nordic"); h.tick(); prepared.join()
            val point = blueprint.building.control!!
            val block = h.world.getBlockAt(point.x, point.y, point.z)
            val confirming = h.service.confirmPreview(h.player); h.tick()
            h.saves.size shouldBe 1
            block.type = Material.DIAMOND_BLOCK
            h.commit()
            (shouldThrow<CompletionException> { confirming.join() }.cause as CivsRejection).reason shouldBe "control-obstructed"
            block.type shouldBe Material.DIAMOND_BLOCK
            val city = h.service.city(h.player)!!
            city.buildings.single().phase shouldBe BuildingPhase.SITE
            city.pendingControls!!.size shouldBe 1
            h.service.cityBusy(h.player) shouldBe false
            val start = h.service.startBuilding(h.player, blueprint.building.id)
            (shouldThrow<CompletionException> { start.join() }.cause as CivsRejection).reason shouldBe "control-position-missing"
            verify(exactly = 0) { h.store.loadPlan(any()) }
            block.type shouldBe Material.DIAMOND_BLOCK
        }
    }

    for (alreadyRemoved in listOf(false, true)) test("restart finishes pending building deletion once with control alreadyRemoved=$alreadyRemoved") {
        serviceScenario { h ->
            val control = CivPosition(10, 63, 0)
            val building = h.city.buildings.last().copy(control = control)
            val block = h.world.getBlockAt(control.x, control.y, control.z)
            if (!alreadyRemoved) {
                block.type = Material.BARREL
                (block.state as org.bukkit.block.Barrel).inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            }
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                buildings = listOf(h.city.buildings.first(), building), pendingDeletion = CivDeletion(building.id))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start(); h.paper.performTicks(20)
            block.type shouldBe Material.AIR
            h.service.city(h.player)!!.pendingDeletion shouldBe CivDeletion(building.id)
            h.service.buildingStatus(building.id) shouldBe "building-status.deleting"
            h.saves.single().first.building(building.id) shouldBe null
            h.commit(); h.time += 6_000; h.paper.performTicks(40)
            h.service.city(h.player)!!.pendingDeletion shouldBe null
            h.service.city(h.player)!!.building(building.id) shouldBe null
            h.world.entities.filterIsInstance<org.bukkit.entity.Item>().sumOf { it.itemStack.amount } shouldBe if (alreadyRemoved) 0 else 3
            h.saves.size shouldBe 1
        }
    }

    test("deleting an active construction fences its late validation and cancels only the selected building") {
        serviceScenario { h ->
            val placement = h.placement()
            h.start(); h.paper.performTicks(20)
            val buildingId = placement.city.construction!!.buildingId
            val deletion = h.service.deleteBuilding(h.player, h.city.id, buildingId)
            h.tick()
            h.saves.single().first.pendingDeletion shouldBe CivDeletion(buildingId)
            h.commit()
            placement.validation.complete(Unit); h.tick()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.saves.size shouldBe 2
            h.commit(); deletion.join() shouldBe Unit
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.building(buildingId) shouldBe null
            h.service.city(h.player)!!.buildings.size shouldBe h.city.buildings.size
        }
    }

    test("active construction accepts another site into an idempotent queue and opens new placement") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val site = active.city.buildings[1].copy(phase = BuildingPhase.SITE)
            val city = active.city.copy(buildings = active.city.buildings.map { if (it.id == site.id) site else it })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            val queued = h.service.startBuilding(h.player, site.id); h.tick()
            h.service.city(h.player)!!.constructionQueue shouldBe null
            h.saves.single().first.constructionQueue shouldBe listOf(site.id)
            h.commit(); queued.join() shouldBe Unit
            h.service.startBuilding(h.player, site.id).join() shouldBe Unit
            h.saves.size shouldBe 1
            h.service.buildingStatus(site.id) shouldBe "building-status.queued"
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join() shouldBe Unit
            h.service.selection(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            verify(exactly = 0) { h.store.loadPlan(site.id) }
        }
    }

    for (background in listOf(false, true)) test("reloaded queue starts its head from fresh terrain and removes that id only with the order commit background=$background") {
        mockkConstructor(CivsChunkLoading::class)
        try {
            var backgroundActive = false
            every { anyConstructed<CivsChunkLoading>().active(any()) } answers { backgroundActive }
            serviceScenario { h ->
                if (!background) {
                    val viewer = h.paper.addPlayer("QueueViewer")
                    viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
                }
                val site = h.city.buildings.last().copy(phase = BuildingPhase.SITE,
                    control = CivPosition(10, 63, 0), entrance = CivPosition(10, 64, 3))
                val hut = site.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT, phase = BuildingPhase.ACTIVE,
                    anchor = CivPosition(20, 64, 0), bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)),
                    entrance = CivPosition(20, 64, 3), control = null)
                h.world.getBlockAt(10, 63, 0).type = Material.BARREL
                h.world.getBlockAt(10, 63, 3).type = Material.DIRT
                val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                    buildings = listOf(h.city.buildings.first(), site, hut), constructionQueue = listOf(site.id),
                    workers = listOf(h.city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1)))
                val immutable = CivPlacementPlan(site.id, city.id, city.worldId, site.copy(phase = BuildingPhase.CONSTRUCTING),
                    listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:dirt", "minecraft:stone")), "a".repeat(64))
                val fresh = immutable.copy(id = UUID.randomUUID(), blocks = immutable.blocks.map { it.copy(before = "minecraft:oak_planks") })
                val snapshot = mockk<ArcSchematicSnapshot>()
                every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
                every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
                every { h.store.loadPlan(site.id) } returns CompletableFuture.completedFuture(immutable)
                every { h.store.loadSchematic(site.id) } returns CompletableFuture.completedFuture(byteArrayOf(1))
                every { h.blueprints.recover(immutable) } returns CompletableFuture.completedFuture(snapshot)
                every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(fresh)
                every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
                every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
                h.start(); h.paper.performTicks(20)
                if (background) {
                    h.paper.server.onlinePlayers.isEmpty() shouldBe true
                    h.saves shouldBe emptyList()
                    h.service.city(h.player)!!.construction shouldBe null
                    h.service.city(h.player)!!.constructionQueue shouldBe listOf(site.id)
                    verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
                    backgroundActive = true
                    h.time += 5_000L
                    h.paper.performTicks(20)
                }
                h.saves.single().first.construction!!.id shouldBe fresh.id
                h.service.city(h.player)!!.constructionQueue shouldBe listOf(site.id)
                verify(exactly = 1) { h.worldAdapter.capture(city.id, city.owner, city.worldId, site.copy(phase = BuildingPhase.CONSTRUCTING), any(), site) }
                verify(exactly = 1) { h.store.savePlan(fresh) }
                h.commit()
                h.service.city(h.player)!!.constructionQueue shouldBe emptyList()
                h.service.city(h.player)!!.construction!!.buildingId shouldBe site.id
            }
        } finally {
            unmockkConstructor(CivsChunkLoading::class)
        }
    }

    for ((autonomous, earthworks) in listOf(true to true, true to false, false to false))
    test("order admission refreshes only autonomous terrain captures before reserving stock autonomous=$autonomous terrain=$earthworks") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            val existing = queued.building.copy(phase = if (earthworks) BuildingPhase.SITE else BuildingPhase.ACTIVE)
            val city = queued.city.copy(autonomy = if (autonomous) CivAutonomy(paused = true) else null,
                inventory = if (autonomous) null else queued.city.inventory,
                stock = if (autonomous) h.city.stock else Stock(),
                buildings = queued.city.buildings.map { if (it.id == existing.id) existing else it })
            val terrain = CivTerraformPlan(CivBox(CivPosition(4, 63, -1), CivPosition(10, 64, 3)),
                listOf(CivPlanBlock(CivPosition(5, 64, 0), "minecraft:dirt", "minecraft:air")),
                listOf(CivPosition(5, 64, 0), CivPosition(6, 64, 0)), 63, 1, 0)
            val target = if (earthworks) existing.copy(phase = BuildingPhase.CONSTRUCTING) else queued.fresh.building
            val plan = queued.fresh.copy(building = target, terrain = terrain.takeIf { earthworks })
            val old = plan.copy(id = existing.id, terrain = terrain.copy(route = listOf(CivPosition(2, 64, 0))))
            val unrelated = old.copy(id = UUID.randomUUID(), cityId = UUID.randomUUID())
            val captures = mutableMapOf(old.id to old, unrelated.id to unrelated)
            val writes = mutableListOf<String>()
            val retained = CompletableFuture<CivPlacementPlan>()
            val bytes = byteArrayOf(2)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveSchematic(any(), any()) } answers {
                secondArg<ByteArray>().toList() shouldBe bytes.toList()
                writes += "schematic:${firstArg<UUID>()}"
                CompletableFuture.completedFuture(Unit)
            }
            every { h.store.savePlan(any()) } answers {
                val capture = firstArg<CivPlacementPlan>()
                writes += "plan:${capture.id}"
                if (capture.id == existing.id) retained.thenApply { captures[it.id] = it; it }
                else CompletableFuture.completedFuture(capture).thenApply { captures[it.id] = it; it }
            }
            h.start()
            val startOrder = CivsService::class.java.getDeclaredMethod("startOrder", UUID::class.java,
                Settlement::class.java, CivPlacementPlan::class.java, ByteArray::class.java).also { it.isAccessible = true }
            val starting = startOrder.invoke(h.service, city.owner, city, plan, bytes) as CompletableFuture<*>
            if (autonomous && earthworks) {
                h.saves shouldBe emptyList()
                starting.isDone shouldBe false
                captures[existing.id] shouldBe old
                retained.complete(plan.copy(id = existing.id)); h.tick()
            } else h.tick()

            val expectedWrites = listOf("schematic:${plan.id}", "plan:${plan.id}") +
                if (autonomous && earthworks) listOf("schematic:${existing.id}", "plan:${existing.id}") else emptyList()
            writes shouldBe expectedWrites
            captures[existing.id] shouldBe if (autonomous && earthworks) plan.copy(id = existing.id) else old
            captures[unrelated.id] shouldBe unrelated
            val reserved = h.saves.single().first
            reserved.construction!!.id shouldBe plan.id
            reserved.construction!!.plannedBuilding shouldBe target
            val cost = if (autonomous) CivsAutonomy.cost(target.type, target.level) else Stock()
            reserved.construction!!.cost shouldBe cost
            reserved.stock shouldBe city.stock - cost
            h.service.settlements().single().stock shouldBe city.stock
            h.commit(); starting.join()
            h.service.settlements().single().stock shouldBe city.stock - cost
            h.saves.size shouldBe 1
        }
    }

    for (failedCapture in listOf("schematic", "plan"))
    test("a retained autonomous $failedCapture failure prevents city reservation and a second charge") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            val existing = queued.building.copy(phase = BuildingPhase.SITE)
            val city = queued.city.copy(autonomy = CivAutonomy(paused = true), inventory = null, stock = h.city.stock,
                buildings = queued.city.buildings.map { if (it.id == existing.id) existing else it })
            val terrain = CivTerraformPlan(CivBox(CivPosition(4, 63, -1), CivPosition(10, 64, 3)),
                listOf(CivPlanBlock(CivPosition(5, 64, 0), "minecraft:dirt", "minecraft:air")),
                listOf(CivPosition(5, 64, 0)), 63, 1, 0)
            val plan = queued.fresh.copy(building = existing.copy(phase = BuildingPhase.CONSTRUCTING), terrain = terrain)
            val failure = IllegalStateException("retained $failedCapture failed")
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.saveSchematic(any(), any()) } answers {
                if (firstArg<UUID>() == existing.id && failedCapture == "schematic") CompletableFuture.failedFuture(failure)
                else CompletableFuture.completedFuture(Unit)
            }
            every { h.store.savePlan(any()) } answers {
                if (firstArg<CivPlacementPlan>().id == existing.id && failedCapture == "plan") CompletableFuture.failedFuture(failure)
                else CompletableFuture.completedFuture(firstArg())
            }
            h.start()
            val startOrder = CivsService::class.java.getDeclaredMethod("startOrder", UUID::class.java,
                Settlement::class.java, CivPlacementPlan::class.java, ByteArray::class.java).also { it.isAccessible = true }
            val starting = startOrder.invoke(h.service, city.owner, city, plan, byteArrayOf(2)) as CompletableFuture<*>
            h.tick()

            CivsDiagnostics.cause(shouldThrow<CompletionException> { starting.join() }) shouldBe failure
            h.saves shouldBe emptyList()
            h.service.settlements().single() shouldBe city
            verify(exactly = 1) { h.store.savePlan(plan) }
            verify(exactly = if (failedCapture == "plan") 1 else 0) { h.store.savePlan(plan.copy(id = existing.id)) }
            verify(exactly = 0) { h.store.save(any()) }
        }
    }

    test("direct upgrade persists its own design behind active construction and duplicate requests keep it immutable") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val building = active.city.buildings[1]
            val blueprint = h.sitePreview(CivBlueprint(building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                control = CivPosition(10, 63, 0)), h.world.uid, mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(2)))
            val captured = CivPlacementPlan(UUID.randomUUID(), active.city.id, h.world.uid, blueprint.building,
                listOf(CivPlanBlock(CivPosition(10, 64, 1), "minecraft:air", "minecraft:stone")))
            every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(captured)
            var savedPlan: CivPlacementPlan? = null
            every { h.store.savePlan(any()) } answers {
                savedPlan = firstArg(); CompletableFuture.completedFuture(firstArg())
            }
            h.start()
            val confirmed = h.service.upgradeBuilding(h.player, building.id); h.tick()
            verify(exactly = 1) { h.blueprints.prepareUpgrade(building, 2, h.world.uid) }
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            h.service.upgradeBuilding(h.player, building.id).isDone shouldBe false
            h.service.city(h.player)!!.constructionQueue shouldBe null
            h.saves.single().first.constructionQueue shouldBe listOf(building.id)
            h.saves.single().first.construction shouldBe active.city.construction
            h.saves.single().first.building(building.id) shouldBe building
            h.world.getBlockAt(10, 63, 0).type shouldBe Material.AIR
            val design = savedPlan!!
            (design.id != building.id) shouldBe true
            design.building.level shouldBe 2
            h.commit(); confirmed.join()
            every { h.store.loadPlan(design.id) } returns CompletableFuture.completedFuture(design)
            val materials = h.service.buildingMaterials(h.player, building.id); h.tick()
            materials.join() shouldBe mapOf("STONE" to 1L)
            val repeated = h.service.upgradeBuilding(h.player, building.id); h.tick(); repeated.join() shouldBe Unit
            h.saves.size shouldBe 1
            verify(exactly = 1) { h.store.saveSchematic(design.id, any()); h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { h.store.saveSchematic(building.id, any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("direct upgrade preparation and capture never expose world preview geometry to boundary pulses") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val player = h.paper.addPlayer("UpgradeMayor").apply {
                isOp = true
                teleport(Location(h.world, 0.0, 65.0, 0.0))
            }
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(active.city.copy(
                members = active.city.members + player.uniqueId)))
            val building = active.city.buildings[1]
            val blueprint = h.sitePreview(CivBlueprint(building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                control = CivPosition(10, 63, 0)), h.world.uid, mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(2)))
            val preparation = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns preparation
            every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture<CivPlacementPlan>()
            h.start()
            val upgrade = h.service.upgradeBuilding(player, building.id)
            h.paper.performTicks(20)
            h.service.preview(player) shouldBe null
            verify(atLeast = 1) { h.boundary.pulse(player, any(), false, null, null, any()) }
            preparation.complete(blueprint); h.paper.performTicks(20)
            upgrade.isDone shouldBe false
            h.service.preview(player) shouldBe null
            verify(atLeast = 2) { h.boundary.pulse(player, any(), false, null, null, any()) }
            verify(exactly = 0) { h.boundary.pulse(player, any(), true, any(), any(), any()); h.renderer.show(any(), any(), any()) }
        }
    }

    for (legacy in listOf(false, true)) test("a reloaded upgrade head defers legacy controls and retains FIFO after a capture failure legacy=$legacy") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade(legacy)
            val viewer = h.paper.addPlayer("UpgradeQueueViewer")
            viewer.teleport(Location(h.world, 0.0, 65.0, 0.0))
            h.start(); h.paper.performTicks(20)
            if (legacy) {
                h.saves.single().first.pendingControls!!.single().buildingId shouldBe queued.building.id
                verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
                h.commit()
                h.world.getBlockAt(10, 63, 0).type shouldBe Material.BARREL
                h.saves.last().first.pendingControls shouldBe emptyList()
                h.commit()
            }
            queued.capture.completeExceptionally(CivsRejection("lands-denied")); h.tick()
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue
            h.service.buildingStatus(queued.building.id) shouldBe "building-status.lands-denied"
            verify(exactly = 0) { h.store.deletePlan(queued.design.id) }
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(queued.fresh)
            h.time += 6_000; h.paper.performTicks(20)
            h.saves.last().first.construction!!.let {
                it.id shouldBe queued.fresh.id
                it.buildingId shouldBe queued.building.id
                it.targetLevel shouldBe 2
            }
            h.saves.last().first.constructionQueue shouldBe queued.city.constructionQueue!!.drop(1)
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.building(queued.building.id)!!.level shouldBe 1
            verify(exactly = 0) { h.store.deletePlan(queued.design.id) }
            h.commit()
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue!!.drop(1)
            verify(exactly = 1) { h.store.deletePlan(queued.design.id); h.store.deleteSchematic(queued.design.id) }
            verify(exactly = 0) { h.store.deletePlan(queued.building.id); h.store.deleteSchematic(queued.building.id) }
        }
    }

    for (known in listOf(false, true)) test("upgrade admission cleans only a known rejected design and retains an unknown commit known=$known") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val building = active.city.buildings[1]
            val blueprint = h.sitePreview(CivBlueprint(building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
                control = CivPosition(10, 63, 0)), h.world.uid, mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf(2)))
            every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(
                CivPlacementPlan(UUID.randomUUID(), active.city.id, h.world.uid, blueprint.building,
                    listOf(CivPlanBlock(CivPosition(10, 64, 1), "minecraft:air", "minecraft:stone"))))
            var savedId: UUID? = null
            every { h.store.savePlan(any()) } answers {
                savedId = firstArg<CivPlacementPlan>().id; CompletableFuture.completedFuture(firstArg())
            }
            val cleanup = CompletableFuture<Boolean>()
            every { h.store.deletePlan(any()) } returns cleanup
            every { h.store.deleteSchematic(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            val confirmed = h.service.upgradeBuilding(h.player, building.id); h.tick()
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            h.saves.single().second.completeExceptionally(if (known) CivsRejection("stale-city") else IllegalStateException("acknowledgement lost"))
            h.tick()
            if (known) {
                confirmed.isDone shouldBe false
                val duplicate = h.service.upgradeBuilding(h.player, building.id)
                duplicate.isDone shouldBe false
                h.saves.size shouldBe 1
                cleanup.complete(true); h.tick()
                (shouldThrow<CompletionException> { duplicate.join() }.cause as CivsRejection).reason shouldBe "stale-city"
            }
            (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe
                if (known) "stale-city" else "storage-unavailable"
            h.service.city(h.player)!!.constructionQueue shouldBe null
            verify(exactly = if (known) 1 else 0) { h.store.deletePlan(savedId!!); h.store.deleteSchematic(savedId!!) }
            verify(exactly = 0) { h.store.deletePlan(building.id); h.store.deleteSchematic(building.id) }
        }
    }

    test("deleting a queued upgrade retires only its separate design after the building deletion commits") {
        serviceScenario { h ->
            val queued = h.queuedUpgrade()
            h.start()
            val deleted = h.service.deleteBuilding(h.player, queued.city.id, queued.building.id); h.tick()
            h.commit()
            verify(exactly = 0) { h.store.deletePlan(queued.design.id) }
            h.commit(); deleted.join()
            h.service.city(h.player)!!.constructionQueue shouldBe queued.city.constructionQueue!!.drop(1)
            verify(exactly = 1) { h.store.deletePlan(queued.design.id); h.store.deleteSchematic(queued.design.id) }
            verify(exactly = 0) { h.store.deletePlan(queued.building.id); h.store.deleteSchematic(queued.building.id) }
        }
    }

    test("town hall controls open the settlement overview and other controls open their building") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val home = h.city.buildings.last().copy(control = CivPosition(10, 63, 0))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city.copy(buildings = listOf(hall, home))))
            val ui = mockk<CivsUi>(relaxed = true)
            h.service.ui = ui
            h.start()
            for (building in listOf(hall, home)) {
                val point = building.control!!
                val block = h.world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
                val event = org.bukkit.event.player.PlayerInteractEvent(h.player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                    null, block, BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND)
                h.service.controlClicked(event); h.tick()
                event.useInteractedBlock() shouldBe org.bukkit.event.Event.Result.DENY
            }
            verify(exactly = 1) { ui.open(h.player); ui.openBuilding(h.player, h.city.id, home.id) }
            verify(exactly = 0) { ui.openBuilding(h.player, h.city.id, hall.id) }
        }
    }

    test("city deletion journals intent before retiring controls and drops contents without the free barrel") {
        serviceScenario { h ->
            val control = CivPosition(0, 63, 0)
            val block = h.world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            val barrel = (block.state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 7))
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL,
                materials = mapOf("OAK_LOG" to 96L),
                buildingMaterials = mapOf(h.city.buildings.first().id.toString() to mapOf("BREAD" to 24L))),
                buildings = h.city.buildings.mapIndexed { i, b -> if (i == 0) b.copy(control = control) else b })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val durable = CompletableFuture<Unit>()
            every { h.store.delete(any()) } returns durable
            h.start()
            val lookup = CompletableFuture<CivsNetworkCity?>()
            every { h.store.lookup(h.owner) } returns lookup
            val pendingLookup = h.service.refreshCity(h.player)
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            val deleted = h.service.deleteCity(h.player, city.id, city.name)
            deleted.isDone shouldBe false
            h.service.city(h.player) shouldBe city
            protection.isProtected(h.world.uid, 0, 63, 0) shouldBe true
            val repeated = h.service.deleteCity(h.player, city.id, city.name)
            repeated.isDone shouldBe false
            block.type shouldBe Material.BARREL
            barrel.getItem(0)!!.amount shouldBe 7
            h.tick()
            h.saves.single().first.pendingDeletion shouldBe CivDeletion(null)
            h.commit()
            (shouldThrow<CompletionException> { repeated.join() }.cause as CivsRejection).reason shouldBe "deletion-pending"
            block.type shouldBe Material.AIR
            h.world.entities.filterIsInstance<org.bukkit.entity.Item>().map { it.itemStack.type to it.itemStack.amount } shouldBe listOf(Material.DIAMOND to 7)
            deleted.isDone shouldBe false
            durable.complete(Unit); h.tick()
            deleted.join() shouldBe Unit
            h.service.city(h.player) shouldBe null
            lookup.complete(CivsNetworkCity(BackendServerId.of("spawn"), city, 1L)); h.tick()
            pendingLookup.isCompletedExceptionally shouldBe true
            h.service.remoteCity(h.player) shouldBe null
            h.service.ready() shouldBe true
            protection.isProtected(h.world.uid, 0, 63, 0) shouldBe false
            block.type shouldBe Material.AIR
            verify(exactly = 1) { h.store.delete(h.saves.single().first); h.boundary.clear(h.owner) }
            h.service.beginFoundingSelection(h.player, city.name, city.style).join() shouldBe Unit
        }
    }

    test("deletion guards cannot reach storage with a stale confirmation or wrong name") {
        serviceScenario { h ->
            h.start()
            for ((id, name, reason) in listOf(Triple(UUID.randomUUID(), h.city.name, "delete-city-changed"),
                Triple(h.city.id, "wrong", "delete-name-mismatch"))) {
                val failure = shouldThrow<CompletionException> { h.service.deleteCity(h.player, id, name).join() }
                (failure.cause as CivsRejection).reason shouldBe reason
            }
            verify(exactly = 0) { h.store.delete(any()) }
            h.service.city(h.player) shouldBe h.city
        }
    }

    test("ambiguous deletion freezes the city until canonical reload without retrying a write") {
        serviceScenario { h ->
            val city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.delete(any()) } returns CompletableFuture.failedFuture(java.io.IOException("ack lost"))
            h.start()
            val deletion = h.service.deleteCity(h.player, city.id, city.name); h.tick(); h.commit()
            (shouldThrow<CompletionException> { deletion.join() }.cause as CivsRejection).reason shouldBe "storage-unavailable"
            h.service.city(h.player) shouldBe h.saves.single().first
            h.service.cityBusy(h.player) shouldBe true
            h.service.deleteCity(h.player, city.id, city.name).isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.store.delete(h.saves.single().first) }
            every { h.store.load() } returns CompletableFuture.completedFuture(emptyList())
            val reload = h.service.reload(h.player); h.tick(); reload.join() shouldBe Unit
            h.service.city(h.player) shouldBe null
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

    test("startup locks recovered native inventories before ready and before the first recovery pulse") {
        serviceScenario { h ->
            val point = CivPosition(5, 63, 0)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
            val barrel = (h.world.getBlockAt(point.x, point.y, point.z).state as org.bukkit.block.Barrel).inventory
            barrel.setItem(0, org.bukkit.inventory.ItemStack(Material.COBBLESTONE, 64))
            val before = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(point)))
            val after = before.copy(revision = before.revision + 1)
            val tx = CivInventoryTransaction(UUID.randomUUID(), before.id, before.revision, after,
                listOf(CivInventorySlotChange(point, "BARREL", 0, null, CivPlainItem("COBBLESTONE", 64))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(before))
            every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
            h.start()
            h.service.ready() shouldBe true
            h.saves.size shouldBe 0
            // The pinned fixture lacks getHolder(false); expose the real barrel's holder and location for this event.
            val eventInventory = mockk<org.bukkit.inventory.Inventory> {
                every { getHolder(false) } returns h.world.getBlockAt(point.x, point.y, point.z).state as org.bukkit.block.Barrel
                every { location } returns barrel.location
            }
            val view = mockk<org.bukkit.inventory.InventoryView>()
            every { view.topInventory } returns eventInventory
            every { view.player } returns h.player
            h.paper.callEvent(org.bukkit.event.inventory.InventoryOpenEvent(view)).isCancelled shouldBe true
            barrel.getItem(0)!!.amount shouldBe 64
        }
    }

    test("founding starts ground selection without reading the player's aim or changing the world") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.player.rayTraceBlocks(any(), any()) } returns null
            h.service.beginFoundingSelection(h.player, "123123", "nordic").join() shouldBe Unit
            h.service.selection(h.player)!!.type shouldBe BuildingType.TOWN_HALL
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) {
                h.player.rayTraceBlocks(any(), any())
                h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any())
                h.worldAdapter.capture(any(), any(), any(), any(), any(), any())
                h.store.save(any())
            }
        }
    }

    for (plant in listOf(Material.SHORT_GRASS, Material.FERN, Material.LARGE_FERN))
        test("explicit click on $plant uses its highlighted support and preserves the founding draft") {
            serviceScenario(empty = true) { h ->
                h.start()
                val support = h.world.getBlockAt(3, 63, 4).also { it.type = Material.GRASS_BLOCK }
                h.world.getBlockAt(3, 64, 4).type = plant
                val clicked = if (plant == Material.LARGE_FERN) h.world.getBlockAt(3, 65, 4).also { it.type = plant }
                    else h.world.getBlockAt(3, 64, 4)
                civsGroundSupport(clicked) shouldBe CivPosition(3, 63, 4)
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                    CompletableFuture.completedFuture(h.previewBlueprint(arg<CivPosition>(5)))
                }
                h.service.beginFoundingSelection(h.player, "123123", "nordic").join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                    CivsGroundTarget(h.world.uid, CivPosition(clicked.x, clicked.y, clicked.z)))
                h.tick(); selected.join()
                h.service.selection(h.player) shouldBe null
                val preview = h.service.preview(h.player)!!
                preview.cityName shouldBe "123123"
                preview.control shouldBe CivPosition(3, 64, -1)
                preview.entrance shouldBe CivPosition(3, 64, 4)
                preview.anchor shouldBe CivPosition(3, 63, 4)
                support.type shouldBe Material.GRASS_BLOCK
                clicked.type shouldBe plant
                verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.store.save(any()) }
            }
        }

    test("an invalid ground click retains the same selection and a later click can prepare the preview") {
        serviceScenario(empty = true) { h ->
            h.start()
            h.service.beginFoundingSelection(h.player, "Oak Town", "nordic").join()
            val selection = h.service.selection(h.player)!!
            val invalid = h.service.selectGround(h.player, selection.id, CivsGroundTarget(h.world.uid, CivPosition(2, 80, 2)))
            (shouldThrow<CompletionException> { invalid.join() }.cause as CivsRejection).reason shouldBe "too-far"
            h.service.selection(h.player) shouldBe selection
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val retry = h.service.selectGround(h.player, selection.id, CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
            h.tick(); retry.join()
            h.service.preview(h.player)!!.cityName shouldBe "Oak Town"
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()); h.store.save(any()) }
        }
    }

    for (exit in listOf("cancel", "menu-reopened", "world-changed", "logout", "expired", "shutdown"))
        test("$exit retires a pending selection before a late blueprint can render") {
            serviceScenario(empty = true) { h ->
                h.start()
                val pending = CompletableFuture<CivBlueprint>()
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
                h.service.beginFoundingSelection(h.player, "First", "nordic").join()
                val id = h.service.selection(h.player)!!.id
                val loading = h.service.selectGround(h.player, id, CivsGroundTarget(h.world.uid, CivPosition(0, 63, 0)))
                when (exit) {
                    "world-changed" -> h.service.worldChanged(org.bukkit.event.player.PlayerChangedWorldEvent(h.player, h.world))
                    "logout" -> h.service.quit(mockk { every { player } returns h.player })
                    "expired" -> { h.time += 120_001; h.service.selection(h.player) shouldBe null }
                    "shutdown" -> h.service.close()
                    else -> h.service.cancelSelection(h.player, exit)
                }
                pending.complete(h.previewBlueprint()); h.tick()
                loading.isCompletedExceptionally shouldBe true
                h.service.preview(h.player) shouldBe null
                h.service.selection(h.player) shouldBe null
                verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
                if (exit != "shutdown") {
                    val retry = h.prepareFounding("Retry", "nordic"); h.tick(); retry.join()
                    h.service.preview(h.player)!!.cityName shouldBe "Retry"
                }
            }
        }

    test("new buildings also wait for an explicit ground click and keep current city ownership") {
        serviceScenario { h ->
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            h.service.selection(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            verify(exactly = 1) { h.boundary.pulse(h.player, h.city, true, null, null, h.city.buildings.associate { it.id to h.service.buildingStatus(it.id) }) }
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            val position = CivPosition(6, 63, 6)
            h.world.getBlockAt(position.x, position.y, position.z).type = Material.GRASS_BLOCK
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                val blueprint = h.previewBlueprint(arg<CivPosition>(5))
                CompletableFuture.completedFuture(blueprint.copy(building = blueprint.building.copy(type = BuildingType.WAREHOUSE)))
            }
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id, CivsGroundTarget(h.world.uid, position))
            h.tick(); selected.join()
            h.service.preview(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            h.service.city(h.player) shouldBe h.city
            verify(exactly = 0) { h.store.save(any()) }
        }
    }

    test("unexpected preparation failure records the operation request and full exception") {
        serviceScenario(empty = true) { h ->
            h.start()
            val records = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { records += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            h.plugin.logger.addHandler(handler)
            try {
                every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    CompletableFuture.completedFuture(h.previewBlueprint())
                val cause = IllegalStateException("preview transport unavailable")
                every { h.renderer.show(any(), any(), any()) } throws cause
                val request = h.prepareFounding("Test", "nordic"); h.tick()
                request.isCompletedExceptionally shouldBe true
                records.any { it.message.startsWith("ArcCivs operation select_ground failed (request=") &&
                    generateSequence(it.thrown) { error -> error.cause }.any { error -> error === cause } } shouldBe true
            } finally { h.plugin.logger.removeHandler(handler) }
        }
    }

    test("remote membership never enters local simulation or mutation and uses a typed backend request") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("spawn")
            val record = CivsNetworkCity(BackendServerId.of("survival"), h.city, 1000)
            every { h.store.lookup(h.owner) } returns CompletableFuture.completedFuture(record)
            val loaded = h.service.refreshCity(h.player); h.tick(); loaded.join()
            h.service.city(h.player) shouldBe null
            h.service.settlements() shouldBe emptyList()
            h.service.remoteCity(h.player) shouldBe record
            val denied = shouldThrow<CompletionException> { h.service.recruit(h.player).join() }.cause as CivsRejection
            denied.reason shouldBe "remote-city"
            val found = shouldThrow<CompletionException> { h.prepareFounding("Second", "nordic").join() }.cause as CivsRejection
            found.reason shouldBe "already-member"
            every { h.transfer.connect(h.player, BackendServerId.of("survival")) } returns BackendTransferResult.SENT
            h.service.transferHome(h.player).join() shouldBe Unit
            verify(exactly = 1) { h.transfer.connect(h.player, BackendServerId.of("survival")) }
            verify(exactly = 0) { h.store.save(any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("fresh network read failure clears remote snapshot and reports an actionable rejection") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("parkour")
            every { h.store.lookup(h.owner) } returns CompletableFuture.completedFuture(CivsNetworkCity(BackendServerId.of("spawn"), h.city, 1000))
            val loaded = h.service.refreshCity(h.player); h.tick(); loaded.join()
            every { h.store.lookup(h.owner) } returns CompletableFuture.failedFuture(IllegalStateException("database offline"))
            val failed = h.service.refreshCity(h.player); h.tick()
            (shouldThrow<CompletionException> { failed.join() }.cause as CivsRejection).reason shouldBe "network-unavailable"
            h.service.remoteCity(h.player) shouldBe null
            h.service.settlements() shouldBe emptyList()
        }
    }

    test("a late membership response cannot replace a newer request") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.store.homeServer() } returns BackendServerId.of("parkour")
            val old = CompletableFuture<CivsNetworkCity?>()
            val recent = CompletableFuture<CivsNetworkCity?>()
            every { h.store.lookup(h.owner) } returnsMany listOf(old, recent)
            val first = h.service.refreshCity(h.player)
            val second = h.service.refreshCity(h.player)
            val current = CivsNetworkCity(BackendServerId.of("spawn"), h.city, 2000)
            recent.complete(current); h.tick(); second.join()
            old.complete(CivsNetworkCity(BackendServerId.of("survival"), h.city, 1000)); h.tick()
            first.isCompletedExceptionally shouldBe true
            h.service.remoteCity(h.player) shouldBe current
        }
    }

    test("view-only config prevents founding before terrain or schematic work") {
        serviceScenario(empty = true, allowedWorlds = emptySet()) { h ->
            h.start()
            h.service.foundingAllowed() shouldBe false
            val reason = shouldThrow<CompletionException> { h.prepareFounding("Test", "nordic").join() }.cause as CivsRejection
            reason.reason shouldBe "founding-disabled"
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()); h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    test("startup storage failure keeps simulation closed and exposes failure status") {
        serviceScenario(empty = true) { h ->
            every { h.store.load() } returns CompletableFuture.failedFuture(IllegalStateException("schema mismatch"))
            val started = h.service.start(); h.tick()
            started.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe false
            h.service.unavailableReason() shouldBe "storage-unavailable"
            h.service.settlements() shouldBe emptyList()
            verify(exactly = 0) { h.citizens.activateNavigating() }
        }
    }

    for (allowedWorld in listOf("vanilla", "survival")) test("founding rejects a disallowed world and accepts a numeric name in $allowedWorld") {
        val config = YamlConfiguration().apply {
            loadFromString(requireNotNull(javaClass.getResourceAsStream("/config.yml"))
                .bufferedReader().use { it.readText() })
        }
        val settings = CivsSettings.read(config)
        settings.worlds shouldBe setOf("vanilla", "survival")
        serviceScenario(empty = true, worldName = allowedWorld, allowedWorlds = settings.worlds) { h ->
            h.start()
            every { h.player.world } returns h.paper.addSimpleWorld("world")
            val rejected = h.prepareFounding("123123", "nordic")
            val reason = shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection
            reason.reason shouldBe "wrong-world"
            reason.values shouldBe mapOf("world" to "world", "worlds" to "survival, vanilla")
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()) }
            verify(exactly = 0) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            every { h.player.world } returns h.world
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val retry = h.prepareFounding("123123", "nordic")
            h.tick()
            retry.join() shouldBe Unit
            h.service.preview(h.player)!!.cityName shouldBe "123123"
        }
    }

    test("world rejection reports the configured world names instead of bundled defaults") {
        serviceScenario(empty = true, allowedWorlds = setOf("custom-realm", "builder-world")) { h ->
            h.start()
            val rejected = h.prepareFounding("Oak Town", "nordic")
            val reason = shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection
            reason.reason shouldBe "wrong-world"
            reason.values shouldBe mapOf("world" to "vanilla", "worlds" to "builder-world, custom-realm")
        }
    }

    test("replacement keeps the prior placement and rejects confirmation until async work resolves") {
        serviceScenario(empty = true) { h ->
            h.start()
            val firstBlueprint = h.previewBlueprint()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(firstBlueprint)
            val first = h.prepareFounding("Oak Town", "nordic")
            h.tick(); first.join()
            val original = h.service.preview(h.player)!!
            val pending = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
            val rotated = h.service.rotatePreview(h.player, -90)
            h.service.preview(h.player) shouldBe original.copy(busy = true)
            h.service.confirmPreview(h.player).isCompletedExceptionally shouldBe true
            verify(exactly = 0) { h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
            pending.completeExceptionally(CivsRejection("source-load-failed"))
            h.tick()
            rotated.isCompletedExceptionally shouldBe true
            h.service.preview(h.player) shouldBe original
            verify(exactly = 1) { h.renderer.show(any(), any(), any()) }
            verify(exactly = 0) { h.renderer.clear(any()) }
        }
    }

    test("four rotations keep the selected placement pivot while the internal control rotates around it") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(h.previewBlueprint(arg<CivPosition>(5), arg<Int?>(7) ?: 0))
            }
            val first = h.prepareFounding("Oak Town", "nordic"); h.tick(); first.join()
            val before = h.service.preview(h.player)!!
            before.anchor shouldBe CivPosition(0, 63, 0)
            before.control shouldBe CivPosition(0, 64, -5)
            val controls = listOf(CivPosition(5, 64, 0), CivPosition(0, 64, 5),
                CivPosition(-5, 64, 0), CivPosition(0, 64, -5))
            for ((index, control) in controls.withIndex()) {
                val rotated = h.service.rotatePreview(h.player, 90); h.tick(); rotated.join()
                h.service.preview(h.player)!!.let {
                    it.anchor shouldBe before.anchor
                    it.entrance shouldBe before.entrance
                    it.rotation shouldBe (index + 1) * 90 % 360
                    it.control shouldBe control
                    (it.id != before.id) shouldBe true
                }
            }
            val left = h.service.rotatePreview(h.player, -90); h.tick(); left.join()
            val nudged = h.service.nudgePreview(h.player, CivsPreviewMove.RIGHT); h.tick(); nudged.join()
            val after = h.service.preview(h.player)!!
            after.anchor shouldBe CivPosition(-1, 63, 0)
            after.control shouldBe CivPosition(-6, 64, 0)
            after.entrance shouldBe CivPosition(-1, 64, 0)
            val right = h.service.rotatePreview(h.player, 90); h.tick(); right.join()
            h.service.preview(h.player)!!.let {
                it.rotation shouldBe 0
                it.anchor shouldBe after.anchor
                it.control shouldBe CivPosition(-1, 64, -5)
            }
        }
    }

    test("explicit and aimed moves replace the placement point without using the internal control as a pivot") {
        serviceScenario(empty = true) { h ->
            h.start()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                CompletableFuture.completedFuture(h.previewBlueprint(arg(5), arg<Int?>(7) ?: 0))
            }
            val first = h.prepareFounding("Oak Town", "nordic"); h.tick(); first.join()
            val rotation = h.service.rotatePreview(h.player, 90); h.tick(); rotation.join()
            val point = CivPosition(4, 62, 3)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.GRASS_BLOCK
            val moved = h.service.movePreview(h.player, CivsGroundTarget(h.world.uid, point)); h.tick(); moved.join()
            h.service.preview(h.player)!!.let {
                it.anchor shouldBe point
                it.entrance shouldBe CivPosition(4, 63, 3)
                it.control shouldBe CivPosition(9, 63, 3)
                it.rotation shouldBe 90
            }
            verify(exactly = 0) { h.player.rayTraceBlocks(any(), any()) }
            val up = h.service.nudgePreview(h.player, CivsPreviewMove.UP); h.tick(); up.join()
            h.service.preview(h.player)!!.anchor shouldBe CivPosition(4, 63, 3)
            h.service.preview(h.player)!!.control shouldBe CivPosition(9, 64, 3)
            val aimed = h.service.movePreview(h.player); h.tick(); aimed.join()
            h.service.preview(h.player)!!.let {
                it.anchor shouldBe CivPosition(0, 63, 0)
                it.control shouldBe CivPosition(5, 64, 0)
                it.rotation shouldBe 90
            }
            verify(exactly = 1) { h.player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER) }
            verify(exactly = 0) { h.store.save(any()); h.worldAdapter.capture(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("cancelled async placement preparation cannot publish a late preview") {
        serviceScenario(empty = true) { h ->
            h.start()
            val pending = CompletableFuture<CivBlueprint>()
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns pending
            val preparing = h.prepareFounding("Oak Town", "nordic")
            h.service.cancelPreview(h.player)
            h.prepareFounding("Another Town", "nordic").isCompletedExceptionally shouldBe true
            verify(exactly = 1) { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            pending.complete(h.previewBlueprint()); h.tick()
            preparing.isCompletedExceptionally shouldBe true
            h.service.preview(h.player) shouldBe null
            verify(exactly = 0) { h.renderer.show(any(), any(), any()) }
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
                CompletableFuture.completedFuture(h.previewBlueprint())
            val next = h.prepareFounding("Another Town", "nordic"); h.tick()
            next.join() shouldBe Unit
            h.service.preview(h.player)!!.cityName shouldBe "Another Town"
        }
    }

    test("one Core heartbeat activates navigation every tick only while the service is ready") {
        serviceScenario { h ->
            var activations = 0
            every { h.citizens.activateNavigating() } answers { activations++; Unit }
            h.paper.performTicks(3)
            activations shouldBe 0
            h.start()
            val started = activations
            h.paper.performTicks(5)
            activations shouldBe started + 5
            h.service.close()
            h.paper.performTicks(5)
            activations shouldBe started + 5
        }
    }

    test("reload and lifecycle cancellation cannot keep an old navigation heartbeat alive") {
        serviceScenario { h ->
            h.start()
            var activations = 0
            every { h.citizens.activateNavigating() } answers { activations++; Unit }
            val pending = CompletableFuture<List<Settlement>>()
            every { h.store.load() } returns pending
            val reload = h.service.reload(h.player)
            h.paper.performTicks(5)
            activations shouldBe 0
            pending.complete(listOf(h.city))
            h.tick()
            reload.join()
            val resumed = activations
            (resumed > 0) shouldBe true
            h.tasks.close()
            h.paper.performTicks(5)
            activations shouldBe resumed
        }
    }

    test("shared protection follows loaded geometry and unregisters at shutdown") {
        serviceScenario { h ->
            val services = h.plugin.server.servicesManager
            val protection = services.load(ArcBlockProtectionService::class.java)!!
            protection.isProtected(h.world.uid, 40, 64, 40) shouldBe true
            h.start()
            protection.isProtected(h.world.uid, 0, 64, 0) shouldBe true
            protection.isProtected(h.world.uid, 40, 64, 40) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            val shifted = h.city.copy(buildings = listOf(h.city.buildings.first().copy(
                bounds = CivBox(CivPosition(30, 63, 30), CivPosition(35, 68, 35)),
            )))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(shifted))
            val reload = h.service.reload(h.player)
            h.tick(); reload.join()
            protection.isProtected(h.world.uid, 0, 64, 0) shouldBe false
            protection.isProtected(h.world.uid, 32, 64, 32) shouldBe true
            h.service.close()
            services.load(ArcBlockProtectionService::class.java) shouldBe null
        }
    }

    test("a site preview and confirmation survive a delayed builder save without losing either revision") {
        serviceScenario { h ->
            val placement = h.placement()
            val source = h.previewBlueprint(CivPosition(5, 63, 5))
            val blueprint = h.sitePreview(source.copy(building = source.building.copy(
                id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
            h.world.getBlockAt(5, 63, 5).type = Material.GRASS_BLOCK
            h.start(); placement.validation.complete(Unit); h.tick()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(5, 63, 5)))
            h.tick(); selected.join()
            h.service.preview(h.player)!!.type shouldBe BuildingType.WAREHOUSE
            val confirmed = h.service.confirmPreview(h.player)
            val duplicate = h.service.confirmPreview(h.player)
            h.tick()
            confirmed.isDone shouldBe false
            duplicate.isDone shouldBe false
            h.saves.size shouldBe 1
            h.world.getBlockAt(5, 64, 0).type shouldBe Material.AIR
            verify(exactly = 0) { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
            h.commit()
            h.saves.size shouldBe 2
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.last().first.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.saves.last().first.constructionQueue shouldBe listOf(blueprint.building.id)
            h.service.city(h.player)!!.building(blueprint.building.id) shouldBe null
            h.world.getBlockAt(5, 64, 0).type shouldBe Material.AIR
            h.commit()
            h.world.getBlockAt(5, 64, 0).type shouldBe Material.BARREL
            h.saves.size shouldBe 3 // Retire the already-durable control receipt.
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick()
            h.saves.size shouldBe 3 // Builder completion waits for receipt retirement.
            h.commit(); confirmed.join(); duplicate.join()
            h.saves.size shouldBe 4
            h.saves.last().first.construction shouldBe null
            h.saves.last().first.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.commit()
            val saved = h.service.city(h.player)!!
            saved.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            saved.construction shouldBe null
            saved.constructionQueue shouldBe listOf(blueprint.building.id)
            saved.pendingControls shouldBe emptyList()
            h.saves.map { it.first.revision } shouldBe (1L..4L).map { placement.city.revision + it }
            verify(exactly = 1) { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
        }
    }

    test("late builder validation waits for a site reservation and keeps its newly committed geometry") {
        serviceScenario { h ->
            val placement = h.placement()
            val source = h.previewBlueprint(CivPosition(5, 63, 5))
            val blueprint = h.sitePreview(source.copy(building = source.building.copy(
                id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
            h.world.getBlockAt(5, 63, 5).type = Material.GRASS_BLOCK
            h.start()
            h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
            val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                CivsGroundTarget(h.world.uid, CivPosition(5, 63, 5)))
            h.tick(); selected.join()
            val confirmed = h.service.confirmPreview(h.player); h.tick()
            h.saves.size shouldBe 1
            placement.validation.complete(Unit); h.tick()
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit()
            h.saves.size shouldBe 2
            h.commit(); confirmed.join()
            h.saves.size shouldBe 3
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.last().first.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            h.saves.last().first.pendingControls shouldBe emptyList()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            h.commit()
            h.service.city(h.player)!!.building(blueprint.building.id)!!.phase shouldBe BuildingPhase.SITE
            verify(exactly = 1) { h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    test("a requested site job waits for the builder save and queues against its newly published state") {
        serviceScenario { h ->
            val placement = h.placement()
            val site = h.city.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
                phase = BuildingPhase.SITE, control = CivPosition(10, 63, 0))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(
                placement.city.copy(buildings = placement.city.buildings.map {
                    if (it.id == h.city.buildings.last().id) site else it
                })))
            h.start(); placement.validation.complete(Unit); h.tick()
            h.saves.size shouldBe 1
            val requested = h.service.startBuilding(h.player, site.id)
            val duplicate = h.service.startBuilding(h.player, site.id)
            h.tick()
            requested.isDone shouldBe false
            duplicate.isDone shouldBe false
            h.saves.size shouldBe 1
            h.commit()
            h.saves.size shouldBe 2
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.last().first.constructionQueue shouldBe listOf(site.id)
            h.service.city(h.player)!!.constructionQueue.orEmpty() shouldBe emptyList()
            h.commit(); requested.join(); duplicate.join()
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(site.id)
            h.saves.size shouldBe 2
            verify(exactly = 0) { h.store.loadPlan(site.id) }
        }
    }

    test("successive site requests retain arrival order across pending city writes") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val first = active.city.buildings[1].copy(phase = BuildingPhase.SITE)
            fun site(x: Int) = first.copy(id = UUID.randomUUID(), anchor = CivPosition(x, 64, 0),
                bounds = CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 68, 2)),
                entrance = CivPosition(x, 64, -2))
            val second = site(30)
            val third = site(40)
            val city = active.city.copy(buildings = active.city.buildings.map { if (it.id == first.id) first else it } + second + third)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            h.start()
            val assignment = h.service.assign(h.player, city.workers.first().id, city.buildings.first().id)
            val requested = h.service.startBuilding(h.player, first.id)
            val next = h.service.startBuilding(h.player, second.id)
            val duplicate = h.service.startBuilding(h.player, first.id)
            h.saves.size shouldBe 1
            h.commit(); assignment.join()
            h.saves.last().first.constructionQueue shouldBe listOf(first.id)
            val last = h.service.startBuilding(h.player, third.id)
            h.commit(); requested.join(); duplicate.join()
            h.saves.last().first.constructionQueue shouldBe listOf(first.id, second.id)
            h.commit(); next.join()
            h.saves.last().first.constructionQueue shouldBe listOf(first.id, second.id, third.id)
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(first.id, second.id)
            h.commit(); last.join()
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(first.id, second.id, third.id)
            h.saves.size shouldBe 4
            verify(exactly = 0) { h.store.loadPlan(first.id); h.store.loadPlan(second.id); h.store.loadPlan(third.id) }
        }
    }

    test("a rejected queue write releases later requests and allows the failed request to retry") {
        serviceScenario { h ->
            val active = h.incrementalPlacement(ConstructionPhase.CONSTRUCTING)
            val first = active.city.buildings[1].copy(phase = BuildingPhase.SITE)
            val second = first.copy(id = UUID.randomUUID(), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)), entrance = CivPosition(30, 64, -2))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(active.city.copy(
                buildings = active.city.buildings.map { if (it.id == first.id) first else it } + second)))
            h.start()
            val rejected = h.service.startBuilding(h.player, first.id)
            val accepted = h.service.startBuilding(h.player, second.id)
            h.saves.single().second.completeExceptionally(CivsRejection("stale-city")); h.tick()
            (shouldThrow<CompletionException> { rejected.join() }.cause as CivsRejection).reason shouldBe "stale-city"
            h.saves.last().first.constructionQueue shouldBe listOf(second.id)
            h.commit(); accepted.join()
            val retry = h.service.startBuilding(h.player, first.id)
            h.saves.last().first.constructionQueue shouldBe listOf(second.id, first.id)
            h.commit(); retry.join()
            h.service.city(h.player)!!.constructionQueue shouldBe listOf(second.id, first.id)
        }
    }

    for (outcome in listOf("cancelled", "unknown", "shutdown"))
        test("a confirmation waiting for a save fails closed after $outcome") {
            serviceScenario { h ->
                val placement = h.placement()
                val source = h.previewBlueprint(CivPosition(5, 63, 5))
                h.sitePreview(source.copy(building = source.building.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)))
                h.world.getBlockAt(5, 63, 5).type = Material.GRASS_BLOCK
                h.start(); placement.validation.complete(Unit); h.tick()
                h.service.beginBuildingSelection(h.player, BuildingType.WAREHOUSE).join()
                val selected = h.service.selectGround(h.player, h.service.selection(h.player)!!.id,
                    CivsGroundTarget(h.world.uid, CivPosition(5, 63, 5)))
                h.tick(); selected.join()
                val confirmed = h.service.confirmPreview(h.player)
                confirmed.isDone shouldBe false
                when (outcome) {
                    "cancelled" -> { h.service.cancelPreview(h.player); h.commit() }
                    "unknown" -> {
                        h.saves.single().second.completeExceptionally(IllegalStateException("acknowledgement lost"))
                        h.tick()
                    }
                    "shutdown" -> { h.service.close(); h.commit() }
                }
                confirmed.isCompletedExceptionally shouldBe true
                h.saves.size shouldBe 1
                h.world.getBlockAt(5, 64, 0).type shouldBe Material.AIR
                verify(exactly = 0) { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any()) }
                if (outcome == "unknown") {
                    (shouldThrow<CompletionException> { confirmed.join() }.cause as CivsRejection).reason shouldBe "storage-unavailable"
                    h.service.confirmPreview(h.player).isCompletedExceptionally shouldBe true
                }
            }
        }

    test("an assignment becomes visible only after its exact durable acknowledgement") {
        serviceScenario { h ->
            h.start()
            val before = h.service.city(h.player)!!
            val result = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            result.isDone shouldBe false
            h.service.city(h.player) shouldBe before
            h.saves.size shouldBe 1
            h.saves.single().first.workers.first().assignment shouldBe h.city.buildings.first().id
            h.commit()
            result.join() shouldBe Unit
            h.service.city(h.player)!!.workers.first().assignment shouldBe h.city.buildings.first().id
            h.service.city(h.player)!!.stock.food shouldBe 24
        }
    }

    test("an unknown write outcome blocks further mutations until authoritative reload") {
        serviceScenario { h ->
            h.start()
            val first = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            val persisted = h.saves.single().first
            h.saves.single().second.completeExceptionally(IllegalStateException("acknowledgement lost"))
            h.tick()
            first.isCompletedExceptionally shouldBe true
            h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id).isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(persisted))
            val reload = h.service.reload(h.player)
            h.tick()
            reload.join() shouldBe Unit
            h.service.city(h.player) shouldBe persisted
            h.service.city(h.player)!!.stock.food shouldBe 24
        }
    }

    test("stale persistence completion after shutdown cannot publish a replacement state") {
        serviceScenario { h ->
            h.start()
            val before = h.service.city(h.player)
            val result = h.service.assign(h.player, h.city.workers.first().id, h.city.buildings.first().id)
            h.tasks.close()
            h.service.close()
            h.commit()
            result.isCompletedExceptionally shouldBe true
            h.service.city(h.player) shouldBe before
            h.service.ready() shouldBe false
        }
    }

    for (knownRejection in listOf(false, true)) test("the final founding slot handles a known rejection=$knownRejection without hiding an uncertain commit") {
        serviceScenario(empty = true, maximumCities = 1) { h ->
            h.start()
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.fileSha256 } returns "a".repeat(64)
            val control = CivPosition(0, 63, 0)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, control = control)
            val blueprint = CivBlueprint(hall, h.world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
            every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
            every { h.worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), true) } answers {
                CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), h.world.uid, hall, emptyList()))
            }
            every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
            every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            val preparing = h.prepareFounding("First Town", "nordic")
            h.tick()
            preparing.join() shouldBe Unit
            val confirm = h.service.confirmPreview(h.player)
            h.tick()
            h.saves.size shouldBe 1
            h.saves.single().first.name shouldBe "First Town"
            h.saves.single().second.completeExceptionally(if (knownRejection) CivsRejection("already-member")
                else IllegalStateException("unknown atomic write outcome"))
            h.tick()
            confirm.isCompletedExceptionally shouldBe true
            h.service.ready() shouldBe knownRejection
            if (knownRejection) {
                (shouldThrow<CompletionException> { confirm.join() }.cause as CivsRejection).reason shouldBe "already-member"
                h.service.preview(h.player)!!.cityName shouldBe "First Town"
            } else h.prepareFounding("Retry", "nordic").isCompletedExceptionally shouldBe true
            h.saves.size shouldBe 1
        }
    }

    for (cursor in 0..1) test("recovered tile $cursor validates only its saved layer and preserves remodeled completed tiles") {
        serviceScenario { h ->
            val building = h.city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
                anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
                entrance = CivPosition(15, 64, 3), phase = BuildingPhase.CONSTRUCTING)
            val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, cursor, ConstructionPhase.PLACING,
                Stock(), plannedBuilding = building, batches = CivConstructionBatches(2, 256), materials = mapOf("STONE" to 2L))
            val city = CivsRules.validate(h.city.copy(stock = Stock(), inventory = CivInventory.starting(Stock(12, 7, 3)),
                buildings = h.city.buildings + building, construction = order))
            val plan = CivPlacementPlan(order.id, city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(15, 65, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(15, 67, 0), "minecraft:air", "minecraft:air"),
            ), "b".repeat(64))
            val full = mockk<ArcSchematicSnapshot>(relaxed = true)
            val part = mockk<ArcSchematicSnapshot>(relaxed = true)
            val secondPart = mockk<ArcSchematicSnapshot>(relaxed = true)
            val operation = mockk<ArcSchematicOperation>(relaxed = true)
            val pasted = CompletableFuture<ArcSchematicMutationResult>()
            val secondOperation = mockk<ArcSchematicOperation>(relaxed = true)
            val secondPasted = CompletableFuture<ArcSchematicMutationResult>()
            var firstAccepted: java.util.function.Consumer<ArcSchematicBlock>? = null
            var secondReady: java.util.function.BooleanSupplier? = null
            val repair = mockk<ArcSchematicOperation>(relaxed = true)
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(full)
            every { h.blueprints.pasteParts(full, building, any()) } answers {
                arg<CivBuildBatch>(2).minimum.y shouldBe 64 + cursor
                CompletableFuture.completedFuture(listOf(part, secondPart))
            }
            every { operation.completion } returns pasted
            every { h.blueprints.paste(part, h.world.uid, building.anchor, any(), any()) } answers {
                firstAccepted = arg(3)
                operation
            }
            every { secondOperation.completion } returns secondPasted
            every { h.blueprints.paste(secondPart, h.world.uid, building.anchor, any(), any()) } answers {
                secondReady = arg(4)
                secondOperation
            }
            every { h.citizens.constructionReady(city.id, order.id) } returns true
            every { repair.completion } returns CompletableFuture<ArcSchematicMutationResult>()
            every { h.blueprints.repair(any(), any()) } returns repair
            // A filled chest in a completed tile would reject a whole-plan native revalidation.
            val remodeled = h.world.getBlockAt(15, 64, 0)
            if (cursor == 1) {
                remodeled.type = Material.CHEST
                (remodeled.state as org.bukkit.block.Container).inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
            }
            CivsWorld(h.plugin, h.tasks, { 0L }) { listOf(city) }.use { nativeWorld ->
                every { h.worldAdapter.revalidate(any(), h.owner) } answers {
                    val batch = firstArg<CivPlacementPlan>()
                    batch.blocks.map { it.position.y } shouldBe listOf(64 + cursor)
                    nativeWorld.revalidate(batch, h.owner)
                }
                h.start()
                verify(exactly = 1) { h.blueprints.paste(part, h.world.uid, building.anchor, any(), any()) }
                verify(exactly = 0) { h.blueprints.paste(full, any(), any(), any(), any()); h.store.saveInventoryTransaction(any()) }
                firstAccepted!!.accept(ArcSchematicBlock(ArcSchematicPoint(15, 64 + cursor, 0), "minecraft:stone", false, false))
                pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.paper.performTicks(1)
                h.saves.size shouldBe 0
                verify(exactly = 1) { h.blueprints.paste(secondPart, h.world.uid, building.anchor, any(), any()) }
                // A new native operation must retain the order's cadence across cropped parts.
                secondReady!!.asBoolean shouldBe false
                h.paper.performTicks(3)
                secondReady!!.asBoolean shouldBe false
                h.paper.performTicks(1)
                secondReady!!.asBoolean shouldBe true
                secondPasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
                h.service.city(h.player)!!.construction!!.workDone shouldBe cursor
                h.commit()
                h.service.city(h.player)!!.inventory shouldBe city.inventory
                if (cursor == 0) {
                    h.service.city(h.player)!!.construction!!.workDone shouldBe 1
                    h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
                    h.paper.performTicks(80)
                    verify(exactly = 0) { h.blueprints.repair(any(), any()) }
                } else {
                    h.service.city(h.player)!!.construction shouldBe null
                    h.service.city(h.player)!!.building(building.id)!!.phase shouldBe BuildingPhase.ACTIVE
                    remodeled.type shouldBe Material.CHEST
                    (remodeled.state as org.bukkit.block.Container).inventory.getItem(0) shouldBe org.bukkit.inventory.ItemStack(Material.DIAMOND, 3)
                }
                verify(exactly = 2) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            }
        }
    }

    test("recovered PLACING validates before paste and commits without charging resources again") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING)
            val orderId = UUID.randomUUID()
            // Exact legacy paid founding snapshot: the new founding API deliberately creates a SITE.
            val recovering = CivsRules.validate(Settlement(h.city.id, h.city.owner, "Recovered", h.world.uid,
                hall.entrance, 96, "nordic", CivsRules.STARTING_STOCK, listOf(hall),
                (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }, construction =
                CivConstruction(orderId, hall.id, 1, 0, 0, ConstructionPhase.PLACING, Stock(), plannedBuilding = hall)))

            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(recovering))
            val plan = CivPlacementPlan(orderId, recovering.id, h.world.uid, hall,
                listOf(CivPlanBlock(hall.anchor, "minecraft:air", "minecraft:stone")), "b".repeat(64))
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { snapshot.blocks() } returns emptyList()
            every { h.store.loadPlan(orderId) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            val validation = CompletableFuture<Unit>()
            every { h.worldAdapter.revalidate(plan, recovering.owner) } returns validation
            val pasted = CompletableFuture<ArcSchematicMutationResult>()
            val operation = mockk<ArcSchematicOperation>(relaxed = true)
            every { operation.completion } returns pasted
            every { h.blueprints.paste(snapshot, h.world.uid, hall.anchor, any(), any()) } returns operation
            val repaired = mockk<ArcSchematicOperation>(relaxed = true)
            every { repaired.completion } returns CompletableFuture<ArcSchematicMutationResult>()
            every { h.blueprints.repair(any(), any()) } returns repaired
            h.world.getChunkAt(hall.entrance.x shr 4, hall.entrance.z shr 4)
            h.start()
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            validation.complete(Unit); h.tick()
            verify(exactly = 1) { h.blueprints.paste(snapshot, h.world.uid, hall.anchor, any(), any()) }
            h.saves.size shouldBe 0
            pasted.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            h.service.city(h.player)!!.construction!!.phase shouldBe ConstructionPhase.PLACING
            h.saves.single().first.stock shouldBe CivsRules.STARTING_STOCK
            h.commit()
            h.service.city(h.player)!!.construction shouldBe null
            h.service.city(h.player)!!.buildings.single().phase shouldBe BuildingPhase.ACTIVE
            h.service.city(h.player)!!.stock shouldBe CivsRules.STARTING_STOCK
        }
    }

    for (writesBeforeRevocation in 0..1) test("Lands revocation after preflight stops write ${writesBeforeRevocation + 1} and pauses the same paid construction") {
        serviceScenario { h ->
            val placement = h.placement()
            val staffed = placement.city.copy(workers = placement.city.workers.mapIndexed { index, worker ->
                if (index == 0) worker.copy(assignment = placement.city.buildings.first().id, nextDeliveryAt = 1L) else worker
            })
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(staffed))
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            var allowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { allowed }
            h.start()
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            placement.validation.complete(Unit); h.tick()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            if (writesBeforeRevocation == 0) allowed = false
            h.commit()
            if (writesBeforeRevocation == 1) {
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                verify(exactly = 1) { h.worldAdapter.canModify(h.owner, h.world.getBlockAt(15, 64, 0), Material.OAK_PLANKS) }
                h.world.getBlockAt(15, 64, 0).type = Material.OAK_PLANKS
                allowed = false
            }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15 + writesBeforeRevocation, 64, 0) shouldBe false
            placement.pasted.completeExceptionally(ArcSchematicException(ArcSchematicFailure.PROTECTED_AREA, "Lands denied the next write"))
            h.tick()
            h.saves.last().first.construction!!.phase shouldBe ConstructionPhase.PAUSED
            h.commit()
            val paused = h.service.city(h.player)!!
            paused.construction!!.id shouldBe placement.city.construction!!.id
            paused.construction.failure shouldBe "protected-area"
            h.service.buildingStatus(paused.construction.buildingId) shouldBe "building-status.protected-area"
            h.service.workerStatus(staffed.workers.first().id) shouldBe "workers.status.protected-area"
            paused.stock shouldBe placement.city.stock
            h.world.getBlockAt(15, 64, 0).type shouldBe if (writesBeforeRevocation == 1) Material.OAK_PLANKS else Material.AIR
            h.world.getBlockAt(16, 64, 0).type shouldBe Material.AIR
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    for (stairState in listOf("before", "after"))
    test("autonomous final write gate tolerates only neighbour-derived $stairState stair shape") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val original = requireNotNull(h.store.loadPlan(order.id).join())
            val snapshot = h.blueprints.recover(original).join()
            val stair = "minecraft:oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]"
            val before = if (stairState == "before") stair else "minecraft:stone"
            val after = if (stairState == "after") stair else "minecraft:stone"
            val plan = original.copy(blocks = original.blocks.mapIndexed { index, cell ->
                if (index == 0) cell.copy(before = before, after = after) else cell
            })
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var landsAllowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { landsAllowed }
            val block = h.world.getBlockAt(15, 64, 0)
            block.blockData = Bukkit.createBlockData(before)
            h.start(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            for (state in listOf(stair, stair.replace("straight", "outer_left"), stair.replace("straight", "inner_right"), "minecraft:stone")) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            }
            for (state in listOf(stair.replace("north", "south"), stair.replace("top", "bottom"),
                stair.replace("false", "true"), stair.replace("oak_stairs", "stone_brick_stairs"), "minecraft:air")) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
                block.blockData.asString shouldBe state
            }
            block.blockData = Bukkit.createBlockData(stair.replace("straight", "outer_right"))
            landsAllowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    for (fenceState in listOf("before", "after"))
    test("autonomous final write gate tolerates only neighbour-derived $fenceState fence connections") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val original = requireNotNull(h.store.loadPlan(order.id).join())
            val snapshot = h.blueprints.recover(original).join()
            val fence = "minecraft:spruce_fence[east=true,north=false,south=false,waterlogged=false,west=true]"
            val observed = "minecraft:spruce_fence[east=true,north=true,south=false,waterlogged=false,west=false]"
            val trapdoor = "minecraft:spruce_trapdoor[facing=east,half=bottom,open=true,powered=false,waterlogged=false]"
            val before = if (fenceState == "before") fence else trapdoor
            val after = if (fenceState == "after") fence else trapdoor
            val plan = original.copy(blocks = original.blocks.mapIndexed { index, cell ->
                if (index == 0) cell.copy(before = before, after = after) else cell
            })
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var landsAllowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { landsAllowed }
            val block = h.world.getBlockAt(15, 64, 0)
            block.blockData = Bukkit.createBlockData(before)
            h.start(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            for (state in listOf(fence, observed,
                "minecraft:spruce_fence[east=false,north=true,south=true,waterlogged=false,west=false]", trapdoor)) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            for (state in listOf(observed.replace("spruce_fence", "oak_fence"), observed.replace("waterlogged=false", "waterlogged=true"),
                "minecraft:air", trapdoor.replace("facing=east", "facing=west"), trapdoor.replace("open=true", "open=false"),
                trapdoor.replace("powered=false", "powered=true"), trapdoor.replace("half=bottom", "half=top"),
                trapdoor.replace("waterlogged=false", "waterlogged=true"))) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
                block.blockData.asString shouldBe state
            }
            block.blockData = Bukkit.createBlockData(observed)
            landsAllowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    for (passage in listOf("door", "fence_gate"))
    test("autonomous final write gate tolerates only $passage opening during an oak-to-spruce upgrade") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val original = requireNotNull(h.store.loadPlan(order.id).join())
            val snapshot = h.blueprints.recover(original).join()
            val before = if (passage == "door") "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"
                else "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            val after = if (passage == "door") "minecraft:spruce_door[facing=north,half=lower,hinge=right,open=false,powered=false]"
                else "minecraft:spruce_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
            val plan = original.copy(blocks = original.blocks.mapIndexed { index, cell ->
                if (index == 0) cell.copy(before = before, after = after) else cell
            })
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var landsAllowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { landsAllowed }
            val block = h.world.getBlockAt(15, 64, 0)
            block.blockData = Bukkit.createBlockData(before)
            h.start(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            for (state in listOf(before, before.replace("open=false", "open=true"),
                after, after.replace("open=false", "open=true"))) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
                block.blockData.asString shouldBe state
            }
            val drifted = listOf(before, after).flatMap { state -> listOf(
                state.replace("facing=north", "facing=south"),
                if (passage == "door") state.replace("half=lower", "half=upper") else state.replace("in_wall=false", "in_wall=true"),
                state.replace("powered=false", "powered=true"),
            ) } + (if (passage == "door") listOf(before.replace("hinge=left", "hinge=right"), after.replace("hinge=right", "hinge=left"))
                else emptyList()) + listOf(before.replace("oak_", "birch_"), "minecraft:air")
            for (state in drifted) {
                block.blockData = Bukkit.createBlockData(state)
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
                block.blockData.asString shouldBe state
            }
            block.blockData = Bukkit.createBlockData(before.replace("open=false", "open=true"))
            landsAllowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    test("autonomous construction preserves a foreign block placed during its delayed begin save") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val order = requireNotNull(placement.city.construction)
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order.copy(payment = null,
                    batches = order.batches!!.copy(atomicPairs = true),
                    cost = CivsAutonomy.cost(requireNotNull(order.plannedBuilding).type, order.targetLevel))))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            h.start()
            h.saves.single().first.construction!!.phase shouldBe ConstructionPhase.PLACING
            val block = h.world.getBlockAt(15, 64, 0)
            block.type = Material.BRICKS
            h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            block.type shouldBe Material.BRICKS
            block.type = Material.AIR
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            block.type = Material.STONE
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            h.service.settlements().single().stock shouldBe village.stock
        }
    }

    test("manual foundations reserve an empty payment and run before an unreachable builder entrance") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = false)
            val old = requireNotNull(placement.city.construction)
            val building = requireNotNull(old.plannedBuilding)
            val cell = CivPlanBlock(CivPosition(15, 63, 0), "minecraft:air", "minecraft:dirt")
            val plan = CivPlacementPlan(old.id, h.city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:oak_planks")), "b".repeat(64),
                CivTerraformPlan(CivBox(cell.position, cell.position), listOf(cell), emptyList(), 63, 0, 1))
            val order = old.copy(phase = ConstructionPhase.READY_TO_PLACE, payment = CivConstructionPayment(),
                batches = CivConstructionBatches(3, 16, true), workRequired = 3, workDone = 0, workCredit = null,
                materials = mapOf("STONE" to 1L, "OAK_PLANKS" to 1L),
                automaticPreparationBatches = 1, preparationWorkplace = null, blocksRequired = 3, blocksPlaced = 0)
            val city = CivsRules.validate(placement.city.copy(construction = order))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(mockk(relaxed = true))
            every { h.citizens.constructionReady(city.id, order.id) } returns false
            every { h.store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            val applied = CompletableFuture<Unit>()
            var gate: (() -> Boolean)? = null
            every { h.worldAdapter.applyTerraform(plan, h.owner, listOf(cell), 0, any(), any()) } answers {
                gate = arg(4)
                applied
            }
            h.start()
            val reserved = h.saves.single().first
            reserved.construction!!.phase shouldBe ConstructionPhase.PLACING
            reserved.construction!!.payment!!.reservedBatch shouldBe 0
            reserved.construction!!.materials shouldBe order.materials
            reserved.inventory shouldBe city.inventory
            gate shouldBe null
            h.commit()
            requireNotNull(gate).invoke() shouldBe true
            applied.complete(Unit); h.tick()
            val finished = h.saves.last().first
            finished.construction!!.workDone shouldBe 1
            finished.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
            finished.construction!!.materials shouldBe order.materials
            finished.construction!!.preparationWorkplace shouldBe building.entrance
            verify(exactly = 0) { h.citizens.constructionReady(city.id, order.id); h.blueprints.paste(any(), any(), any(), any(), any()) }
        }
    }

    for (resumedBatch in 0..1) test("autonomous earthworks resume batch $resumedBatch with actual worker admission and durable next front") {
        serviceScenario { h ->
            val placement = h.incrementalPlacement(paid = true)
            val old = requireNotNull(placement.city.construction)
            val building = requireNotNull(old.plannedBuilding)
            val cells = listOf(CivPlanBlock(CivPosition(5, 64, 0), "minecraft:dirt", "minecraft:air"),
                CivPlanBlock(CivPosition(7, 63, 0), "minecraft:air", "minecraft:cobblestone"))
            val route = (0..12).map { CivPosition(it, 64, 0) }
            val plan = CivPlacementPlan(old.id, h.city.id, h.world.uid, building, listOf(
                CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
                CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:oak_planks")), "b".repeat(64),
                CivTerraformPlan(CivBox(CivPosition(5, 63, 0), CivPosition(7, 64, 0)), cells, route, 63, 1, 1))
            val prefix = civsTerraformBatches(plan)
            val order = old.copy(phase = ConstructionPhase.PLACING, payment = null,
                cost = CivsAutonomy.cost(building.type, old.targetLevel),
                batches = CivConstructionBatches(4, 16, true), workRequired = 4, workDone = resumedBatch, workCredit = 2,
                blocksRequired = 4, blocksPlaced = resumedBatch, preparationWorkplace = prefix[resumedBatch].workplace)
            val village = CivsRules.validate(placement.city.copy(autonomy = CivAutonomy(), inventory = null,
                stock = Stock(40, 30, 20), construction = order))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(village))
            every { h.store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
            val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
            every { h.blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
            var gate: (() -> Boolean)? = null
            val applied = CompletableFuture<Unit>()
            var placed: ((CivPlanBlock) -> Unit)? = null
            every { h.citizens.constructionPlaced(village.id, order.id, any(), any()) } throws IllegalStateException("cosmetic packet failure")
            every { h.worldAdapter.applyTerraform(plan, h.owner, any(), resumedBatch, any(), any()) } answers {
                arg<List<CivPlanBlock>>(2) shouldBe listOf(cells[resumedBatch])
                gate = arg(4)
                placed = arg(5)
                applied
            }
            every { h.citizens.constructionReady(village.id, order.id) } returns false
            h.start()
            verify(exactly = 1) { h.worldAdapter.applyTerraform(plan, h.owner, any(), resumedBatch, any(), any()) }
            verify(exactly = 0) { h.blueprints.paste(any(), any(), any(), any(), any()) }
            requireNotNull(gate).invoke() shouldBe false
            every { h.citizens.constructionReady(village.id, order.id) } returns true
            requireNotNull(gate).invoke() shouldBe true
            h.saves.size shouldBe 0
            // A gesture/packet failure after a native write must not pause the paid earthwork order.
            requireNotNull(placed).invoke(cells[resumedBatch])
            applied.complete(Unit); h.tick()
            val saved = h.saves.single().first
            saved.construction!!.workDone shouldBe resumedBatch + 1
            saved.construction!!.preparationWorkplace shouldBe (prefix.getOrNull(resumedBatch + 1)?.workplace ?: route.last())
            saved.construction!!.blocksPlaced shouldBe resumedBatch + 1
            saved.stock shouldBe village.stock
            h.service.settlements().single().construction!!.workDone shouldBe resumedBatch
            // Persist before publishing; closing here simulates a restart at this durable boundary.
            h.saves.single().second.complete(saved)
            h.service.close()
        }
    }

    test("a player container filled after preflight keeps its items at the exact native write boundary") {
        // WorldEdit clears container contents before setBlock, even for the same target material.
        listOf(Material.OAK_PLANKS, Material.CHEST).forEach { target ->
            serviceScenario { h ->
                val placement = h.placement(target)
                h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
                val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
                val block = h.world.getBlockAt(15, 64, 0)
                block.type = Material.CHEST
                val contents = (block.state as org.bukkit.block.Container).inventory
                contents.setItem(0, org.bukkit.inventory.ItemStack(Material.DIAMOND, 3))
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
                contents.getItem(0)!!.amount shouldBe 3
                contents.clear()
                protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            }
        }
    }

    test("placement guards reject unloaded chunks and asynchronous calls without touching Lands or loading chunks") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            h.world.unloadChunk(1, 0)
            h.world.isChunkLoaded(1, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 16, 64, 0) shouldBe false
            h.world.isChunkLoaded(1, 0) shouldBe false
            val worldId = h.world.uid
            CompletableFuture.supplyAsync {
                protection.permitsSchematicMutation("arccivs-construction", worldId, 15, 64, 0)
            }.join() shouldBe false
            verify(exactly = 0) { h.worldAdapter.canModify(any(), any(), any()) }
            h.world.loadChunk(1, 0)
            protection.permitsSchematicMutation("arccivs-construction", worldId, 16, 64, 0) shouldBe true
            verify(exactly = 1) { h.worldAdapter.canModify(h.owner, h.world.getBlockAt(16, 64, 0), Material.STONE) }
        }
    }

    test("repair retains only its planned footprint and checks live neighbor materials without an unprotected fallback") {
        serviceScenario { h ->
            val placement = h.placement()
            var allowed = true
            every { h.worldAdapter.canModify(h.owner, any(), any()) } answers { allowed }
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick(); h.commit()
            h.service.city(h.player)!!.construction shouldBe null
            val neighbor = h.world.getBlockAt(18, 64, 0).also { it.type = Material.OAK_FENCE }
            protection.isProtected(h.world.uid, 18, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe true
            verify(exactly = 1) { h.worldAdapter.canModify(h.owner, neighbor, Material.OAK_FENCE) }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 19, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 0, 64, 0) shouldBe false
            allowed = false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            allowed = true
            placement.repaired.complete(ArcSchematicMutationResult(1, 0, false)); h.tick()
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 18, 64, 0) shouldBe false
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("cancelled placement releases its guard and retains the paid paused order") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            placement.pasted.complete(ArcSchematicMutationResult(1, 0, true)); h.tick(); h.commit()
            val city = h.service.city(h.player)!!
            city.construction!!.phase shouldBe ConstructionPhase.PAUSED
            city.construction.failure shouldBe "placement-cancelled"
            city.stock shouldBe placement.city.stock
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }

    test("closing a service observes actual citizen retirement and duplicate close keeps that barrier") {
        serviceScenario { h ->
            val terminal = CompletableFuture<Void>()
            every { h.citizens.closeAsync() } returns terminal
            h.service.close()
            h.service.close()
            verify(exactly = 1) { h.citizens.closeAsync() }
            h.service.drained().isDone shouldBe false
            terminal.complete(null)
            h.service.drained().join()
        }
    }

    test("closing an active placement releases its guard and a late completion cannot start repair") {
        serviceScenario { h ->
            val placement = h.placement()
            every { h.worldAdapter.canModify(h.owner, any(), any()) } returns true
            h.start(); placement.validation.complete(Unit); h.tick(); h.commit()
            val protection = h.plugin.server.servicesManager.load(ArcBlockProtectionService::class.java)!!
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe true
            h.service.close()
            verify(exactly = 1) { placement.operation.cancel() }
            placement.pasted.complete(ArcSchematicMutationResult(2, 0, false)); h.tick()
            h.saves.size shouldBe 1
            verify(exactly = 0) { h.blueprints.repair(any(), any()) }
            protection.permitsSchematicMutation("arccivs-construction", h.world.uid, 15, 64, 0) shouldBe false
            h.service.health().activeLeases shouldBe 0
        }
    }
})

private class ServiceRoadUpkeep(private val h: ServiceScenario) {
    private val hut = h.city.buildings.last().copy(type = BuildingType.BUILDER_HUT)
    private val floor = CivPosition(20, 63, 0)
    private val road = CivBuilding(UUID.randomUUID(), BuildingType.ROAD, 1, floor, 0, "civs/infrastructure/test-road",
        CivBox(floor, floor.copy(y = 65)), floor.copy(y = 64), BuildingPhase.ACTIVE,
        transit = listOf(floor.copy(y = 64)))
    val worker = h.city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1)
    val city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null,
        buildings = listOf(h.city.buildings.first(), hut, road), workers = listOf(worker)))
    val work = CivWorldWork(worker.id, worker.completedCycles, hut.id, floor.copy(x = 19, y = 64),
        listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")), status = "repairing-road", focus = floor,
        upkeep = CivRoadUpkeep(city.id, city.worldId, city.owner, road.id, road, floor, "minecraft:cobblestone", null, emptyList()))
    private val plan = CivPlacementPlan(road.id, city.id, city.worldId, road,
        listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")), "a".repeat(64))

    init {
        h.world.loadChunk(1, 0)
        h.world.getBlockAt(20, 63, 0).type = Material.DIRT
        h.world.getBlockAt(20, 62, 0).type = Material.STONE
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
        every { h.worldAdapter.captureRoadUpkeep(any(), worker, any()) } answers {
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
        .also { it.isAccessible = true }.invoke(h.service, city.id, worker.id, hut.id, worker.completedCycles,
            worker.nextDeliveryAt, null, h.time) as CompletableFuture<Settlement>
}

/** A distinct paused Auto city makes the target, domain owner and grant-free admission observable. */
private class ServiceAutoBoard(private val h: ServiceScenario) {
    private val buildingId = UUID.randomUUID()
    private val anchor = CivPosition(262, 63, 10)
    val target = CivsGroundTarget(h.world.uid, anchor)
    val city: Settlement
    var blueprint: CivBlueprint
        private set
    val capture = CompletableFuture<CivPlacementPlan>()

    init {
        fun shifted(point: CivPosition) = point.copy(x = point.x + 256)
        city = CivsRules.validate(h.city.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), name = "Auto Board Town",
            center = shifted(h.city.center), inventory = null, workers = emptyList(), memberNames = null,
            autonomy = CivAutonomy(paused = true), immigration = CivImmigration(0, h.time),
            buildings = h.city.buildings.map { it.copy(id = UUID.randomUUID(), anchor = shifted(it.anchor), entrance = shifted(it.entrance),
                bounds = CivBox(shifted(it.bounds.min), shifted(it.bounds.max)), control = it.control?.let(::shifted)) }))
        blueprint = blueprint(anchor, 0)
        h.world.loadChunk(anchor.x shr 4, anchor.z shr 4)
        h.world.getBlockAt(anchor.x, anchor.y, anchor.z).type = Material.GRASS_BLOCK
        every { h.player.location } returns Location(h.world, 262.0, 65.0, 6.0)
        every { h.player.eyeLocation } returns Location(h.world, 262.0, 66.62, 6.0)
        every { h.store.load() } returns CompletableFuture.completedFuture(listOf(h.city, city))
        every { h.blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            blueprint = blueprint(arg(5), arg<Int?>(7) ?: 0)
            CompletableFuture.completedFuture(blueprint)
        }
        every { h.worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val building = arg<CivBuilding>(3)
            CompletableFuture.completedFuture(CivTerraformPlan(building.bounds, emptyList(), emptyList(), 63, 0, 0))
        }
        every { h.worldAdapter.captureAutonomousSite(any(), any(), any(), any(), any(), any()) } returns capture
        every { h.worldAdapter.captureSite(any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(plan(firstArg(), arg(2), arg(3)))
        }
        every { h.store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { h.store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
    }

    private fun blueprint(at: CivPosition, rotation: Int): CivBlueprint {
        val original = h.previewBlueprint(at, rotation)
        every { original.snapshot.fileSha256 } returns "a".repeat(64)
        return original.copy(building = original.building.copy(id = buildingId, type = BuildingType.PLAN_BOARD,
            schematicId = CivsPlanBoardBlueprint.SCHEMATIC_ID))
    }

    fun plan(cityId: UUID = city.id, worldId: UUID = city.worldId, building: CivBuilding = blueprint.building): CivPlacementPlan =
        CivPlacementPlan(UUID.randomUUID(), cityId, worldId, building,
            listOf(CivPlanBlock(building.bounds.min, "minecraft:air", "minecraft:stone")), "a".repeat(64),
            CivTerraformPlan(building.bounds, emptyList(), emptyList(), 63, 0, 0))
}

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

private fun serviceScenario(empty: Boolean = false, maximumCities: Int = 500, worldName: String = "vanilla",
    allowedWorlds: Set<String> = setOf("vanilla"), storageFactory: ((org.bukkit.plugin.Plugin) -> CivsStorage)? = null,
    nativeGeometry: Boolean = false,
    action: (ServiceScenario) -> Unit) =
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper -> ServiceScenario(paper, empty, maximumCities, worldName, allowedWorlds, storageFactory, nativeGeometry).use(action) }
    }

private class ServiceScenario(val paper: MockBukkitTestRuntime, empty: Boolean, maximumCities: Int,
    worldName: String, allowedWorlds: Set<String>, storageFactory: ((org.bukkit.plugin.Plugin) -> CivsStorage)?, nativeGeometry: Boolean) : AutoCloseable {
    val plugin = paper.createSimplePlugin("CivsServiceTest")
    val world = if (nativeGeometry) CivsWorksiteWorldFixture(worldName).also(paper.server::addWorld) else paper.addSimpleWorld(worldName)
    val player = mockk<Player>(relaxed = true)
    val store = mockk<CivsStore>()
    val blueprints = mockk<CivsBlueprints>()
    val worldAdapter = mockk<CivsWorld>(relaxed = true)
    val renderer = mockk<CivsPreviewRenderer>(relaxed = true)
    val citizens = mockk<CivsCitizens>(relaxed = true)
    val boundary = mockk<CivsBoundaryRenderer>(relaxed = true)
    val transfer = mockk<BackendTransfer>()
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val saves = mutableListOf<Pair<Settlement, CompletableFuture<Settlement>>>()
    val owner = UUID.randomUUID()
    var time = System.currentTimeMillis()
    val city: Settlement
    val service: CivsService

    init {
        every { worldAdapter.canModify(any(), any(), any()) } returns true
        every { worldAdapter.captureManualFoundation(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            CompletableFuture.completedFuture(null)
        every { worldAdapter.captureSite(any(), any(), any(), any(), any(), any(), isNull(), any()) } answers {
            worldAdapter.capture(firstArg(), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6), arg(7))
        }
        every { blueprints.pasteParts(any(), any(), isNull()) } answers { CompletableFuture.completedFuture(listOf(firstArg())) }
        every { blueprints.availableStyles(any()) } answers {
            when (firstArg<BuildingType?>()) {
                BuildingType.PLAN_BOARD -> listOf("plan-board")
                BuildingType.DECORATION -> emptyList()
                else -> CivsBlueprints.STYLES
            }
        }
        every { blueprints.maximumLevel(any()) } answers { firstArg<CivBuilding>().type.maxLevel }
        every { blueprints.prepareUpgrade(any(), any(), any()) } answers {
            val building = firstArg<CivBuilding>()
            check(!building.schematicId.startsWith("civs/v2/")) { "Catalog upgrades need an explicit fixture" }
            val style = CivsBlueprints.styleOf(building) ?: throw CivsRejection("invalid-style")
            blueprints.prepare(style, building.type, arg(1), building.id, arg(2), building.anchor,
                building.rotation / 90, building.rotation, building.entrance)
        }
        every { citizens.closeAsync() } returns CompletableFuture.completedFuture(null)
        every { renderer.drained() } returns CompletableFuture.completedFuture(null)
        every { boundary.drained() } returns CompletableFuture.completedFuture(null)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        every { citizens.autonomousVisualWork(any(), any()) } returns null
        every { citizens.pendingStockDelivery(any(), any()) } returns null
        every { store.markConsumedItems(any()) } answers { CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY.adding(
            firstArg<CivInventoryTransaction>().collected?.ground.orEmpty().map(CivGroundItemChange::entityId))) }
        every { player.uniqueId } returns owner
        every { player.world } returns world
        every { player.location } returns Location(world, 0.0, 65.0, 0.0)
        every { player.eyeLocation } returns Location(world, 0.0, 66.62, 0.0)
        every { player.hasPermission(any<String>()) } returns true
        every { player.isOnline } returns true
        world.loadChunk(0, 0)
        val ground = world.getBlockAt(0, 63, 0).also { it.type = Material.GRASS_BLOCK }
        every { player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER) } returns RayTraceResult(ground.location.toVector(), ground, BlockFace.UP)
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 2, CivPosition(0, 64, 0), 0,
            CivsBlueprints.schematicId("nordic", BuildingType.TOWN_HALL, 2), CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)),
            CivPosition(0, 64, -2), BuildingPhase.ACTIVE)
        val home = hall.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, level = 1, anchor = CivPosition(10, 64, 0),
            schematicId = CivsBlueprints.schematicId("nordic", BuildingType.HOUSE, 1),
            bounds = CivBox(CivPosition(8, 63, -2), CivPosition(12, 68, 2)), entrance = CivPosition(10, 64, -2))
        city = CivsRules.validate(Settlement(UUID.randomUUID(), owner, "Oak Town", world.uid, CivPosition(0, 64, 0), 96,
            "nordic", Stock(96, 96, 24), listOf(hall, home), (1..4).map { CivWorker(UUID.randomUUID(), "Worker $it", null) }, createdAt = time))
        every { store.load() } returns CompletableFuture.completedFuture(if (empty) emptyList() else listOf(city))
        every { store.loadPlan(any()) } returns CompletableFuture.completedFuture(null)
        every { store.homeServer() } returns null
        every { store.lookup(any()) } returns CompletableFuture.completedFuture(null)
        every { store.save(any()) } answers {
            CompletableFuture<Settlement>().also { saves += firstArg<Settlement>() to it }
        }
        service = CivsService(plugin, tasks, CivsSettings(Path.of("/tmp/civs-unused"), allowedWorlds, 96,
            maximumCities, 50_000, 4, 120, 48, 96), store, blueprints, Executor(Runnable::run), worldAdapter, renderer, citizens,
            backendTransfer = transfer, clock = { time }, boundaryRenderer = boundary,
            storageAdapter = storageFactory?.invoke(plugin) ?: CivsStorage(plugin) { emptyArray() },
            supplyExecutor = Executor(Runnable::run))
    }
    fun prepareFounding(name: String, style: String): CompletableFuture<Unit> =
        service.beginFoundingSelection(player, name, style).thenCompose {
            service.selectGround(player, service.selection(player)!!.id, CivsGroundTarget(world.uid, CivPosition(0, 63, 0)))
        }
    fun start() { val loaded = service.start(); tick(); await(loaded) shouldBe Unit }
    fun tick() = paper.performTicks(8)
    fun tickUntil(description: String, condition: () -> Boolean) {
        repeat(80) {
            if (condition()) return
            paper.performTicks(1)
        }
        check(condition()) { "$description did not complete within 80 scheduler ticks" }
    }
    fun prepareCarpentry(stock: Map<String, Long> = emptyMap()): Pair<CivBuilding, CivWorker> {
        val workshop = city.buildings.last().copy(type = BuildingType.SAWMILL,
            control = CivPosition(10, 63, 0), production = null)
        val smith = workshop.copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH,
            anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, -2), control = CivPosition(20, 63, 0),
            bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), production = "IRON_PICKAXE")
        val carpenter = city.workers.first().copy(assignment = workshop.id, nextDeliveryAt = 1)
        val smithWorker = city.workers[1].copy(assignment = smith.id, nextDeliveryAt = 1)
        val ready = CivsRules.validate(city.copy(buildings = listOf(city.buildings.first(), workshop, smith),
            workers = listOf(carpenter, smithWorker), stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL,
                buildingMaterials = mapOf(workshop.id.toString() to stock))))
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        return workshop to carpenter
    }
    fun craftJob(workerId: UUID): CivCraftJob {
        repeat(80) {
            val current = requireNotNull(service.city(player))
            service.craftPlan(current, current.workers.first { it.id == workerId })?.let { return it }
            paper.performTicks(1)
        }
        error("Supply dispatcher did not assign a carpenter batch within80 ticks")
    }
    fun supplyPlan(workerId: UUID): CivCourierPlan {
        repeat(80) {
            val current = requireNotNull(service.city(player))
            service.courierPlan(current, current.workers.first { it.id == workerId })?.let { return it }
            paper.performTicks(1)
        }
        error("Supply dispatcher did not assign worker $workerId within80 ticks")
    }
    fun <T> await(future: CompletableFuture<T>): T {
        repeat(80) {
            if (future.isDone) return future.join()
            paper.performTicks(1)
        }
        check(future.isDone) { "Service future did not finish within 80 ticks; pending saves=${saves.count { !it.second.isDone }}" }
        return future.join()
    }
    fun supplySite(owner: UUID, logs: Int): CivPlacementPlan {
        val site = city.buildings.last().copy(type = BuildingType.BUILDER_HUT, phase = BuildingPhase.SITE, control = CivPosition(10, 63, 0))
        world.getBlockAt(10, 63, 0).type = Material.BARREL
        val ready = city.copy(owner = owner, memberNames = paper.server.getPlayer(owner)?.name?.let { mapOf(owner.toString() to it) },
            stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = listOf(city.buildings.first(), site))
        val plan = CivPlacementPlan(site.id, ready.id, world.uid, site.copy(phase = BuildingPhase.CONSTRUCTING),
            List(logs) { i -> CivPlanBlock(CivPosition(8 + i % 5, 64 + i / 25, -2 + i / 5 % 5),
                "minecraft:air", "minecraft:oak_log[axis=y]") }, "a".repeat(64))
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        every { store.loadPlan(site.id) } returns CompletableFuture.completedFuture(plan)
        return plan
    }
    fun sitePreview(blueprint: CivBlueprint = previewBlueprint()): CivBlueprint {
        every { blueprint.snapshot.fileSha256 } returns "a".repeat(64)
        every { blueprints.prepare(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(blueprint)
        every { worldAdapter.capture(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompletableFuture.completedFuture(CivPlacementPlan(UUID.randomUUID(), firstArg(), world.uid, blueprint.building,
                emptyList(), adminPlacement = arg(7)))
        }
        every { store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        return blueprint
    }
    fun previewBlueprint(anchor: CivPosition = CivPosition(0, 63, 5), rotation: Int = 0): CivBlueprint {
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        val entrance = anchor.copy(y = anchor.y + 1)
        val offset = listOf(CivPosition(0, 1, -5), CivPosition(5, 1, 0), CivPosition(0, 1, 5), CivPosition(-5, 1, 0))[rotation / 90]
        val control = CivPosition(anchor.x + offset.x, anchor.y + offset.y, anchor.z + offset.z)
        val corners = listOf(CivPosition(-2, 0, -5), CivPosition(2, 5, -1)).map {
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(it.x, it.y, it.z), rotation / 90)
        }
        val hall = city.buildings.first().copy(level = 1, phase = BuildingPhase.CONSTRUCTING, anchor = anchor,
            entrance = entrance, rotation = rotation, control = control,
            bounds = CivBox(CivPosition(anchor.x + corners.minOf { it.x }, anchor.y, anchor.z + corners.minOf { it.z }),
                CivPosition(anchor.x + corners.maxOf { it.x }, anchor.y + 5, anchor.z + corners.maxOf { it.z })))
        return CivBlueprint(hall, world.uid, snapshot, emptyList(), emptyList(), byteArrayOf(1))
    }
    fun commit() { saves.last().let { it.second.complete(it.first) }; tick() }
    fun queuedUpgrade(legacy: Boolean = false): ServiceQueuedUpgrade {
        val building = city.buildings.last().copy(entrance = CivPosition(10, 64, 3),
            control = if (legacy) null else CivPosition(10, 63, 0))
        val hut = building.copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT, control = null,
            anchor = CivPosition(20, 64, 0), bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)), entrance = CivPosition(20, 64, 3))
        val site = building.copy(id = UUID.randomUUID(), phase = BuildingPhase.SITE, control = CivPosition(30, 63, 0),
            anchor = CivPosition(30, 64, 0), bounds = CivBox(CivPosition(28, 63, -2), CivPosition(32, 68, 2)), entrance = CivPosition(30, 64, 3))
        val queued = CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = listOf(city.buildings.first(), building, hut, site), constructionQueue = listOf(building.id, site.id),
            workers = listOf(city.workers.first().copy(assignment = hut.id, nextDeliveryAt = 1))))
        val target = building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING, control = CivPosition(10, 63, 0))
        val id = UUID.nameUUIDFromBytes("arccivs-upgrade/${building.id}/2".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val design = CivPlacementPlan(id, queued.id, world.uid, target,
            listOf(CivPlanBlock(CivPosition(10, 64, 0), "minecraft:air", "minecraft:stone")), "a".repeat(64))
        val fresh = design.copy(id = UUID.randomUUID(), blocks = design.blocks.map { it.copy(before = "minecraft:oak_planks") })
        val snapshot = mockk<ArcSchematicSnapshot>()
        every { snapshot.blocks() } returns listOf(ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), "minecraft:stone", false, false))
        val capture = CompletableFuture<CivPlacementPlan>()
        every { store.load() } returns CompletableFuture.completedFuture(listOf(queued))
        every { store.loadPlan(id) } returns CompletableFuture.completedFuture(design)
        every { store.loadSchematic(id) } returns CompletableFuture.completedFuture(byteArrayOf(2))
        every { blueprints.recover(design) } returns CompletableFuture.completedFuture(snapshot)
        every { worldAdapter.capture(any(), any(), any(), any(), any(), any()) } returns capture
        every { store.saveSchematic(any(), any()) } returns CompletableFuture.completedFuture(Unit)
        every { store.savePlan(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deletePlan(any()) } returns CompletableFuture.completedFuture(true)
        every { store.deleteSchematic(any()) } returns CompletableFuture.completedFuture(true)
        world.loadChunk(1, 0)
        world.getBlockAt(10, 63, 3).type = Material.DIRT
        if (!legacy) world.getBlockAt(10, 63, 0).type = Material.BARREL
        return ServiceQueuedUpgrade(queued, building, design, fresh, capture)
    }
    fun incrementalPlacement(phase: ConstructionPhase = ConstructionPhase.READY_TO_PLACE, paid: Boolean = false, stock: Int = 0): ServicePlacement {
        world.getBlockAt(0, 63, 0).type = Material.BARREL
        world.getBlockAt(15, 63, 1).type = Material.BARREL
        world.getBlockAt(20, 63, 0).type = Material.BARREL
        if (stock > 0) (world.getBlockAt(20, 63, 0).state as org.bukkit.block.Barrel).inventory
            .setItem(0, org.bukkit.inventory.ItemStack(Material.STONE, stock))
        val building = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
            anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
            entrance = CivPosition(15, 64, 3), control = CivPosition(15, 63, 1), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), building.id, 1, 2, 0, phase, Stock(),
            failure = if (phase == ConstructionPhase.PAUSED) "placement-cancelled" else null, plannedBuilding = building,
            batches = CivConstructionBatches(2, 16), materials = if (paid) mapOf("OAK_PLANKS" to 1L) else mapOf("STONE" to 1L, "OAK_PLANKS" to 1L),
            payment = CivConstructionPayment(reservedBatch = if (paid) 0 else null))
        val hut = city.buildings.last().copy(id = UUID.randomUUID(), type = BuildingType.BUILDER_HUT,
            anchor = CivPosition(20, 64, 0), entrance = CivPosition(20, 64, 3), control = CivPosition(20, 63, 0),
            bounds = CivBox(CivPosition(18, 63, -2), CivPosition(22, 68, 2)))
        val ready = CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = city.buildings.mapIndexed { index, b -> if (index == 0) b.copy(control = CivPosition(0, 63, 0)) else b } + hut + building,
            workers = city.workers.mapIndexed { index, worker -> if (index == 0) worker.copy(assignment = hut.id, nextDeliveryAt = 1) else worker },
            construction = order))
        val plan = CivPlacementPlan(order.id, city.id, world.uid, building, listOf(
            CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", "minecraft:stone"),
            CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:oak_planks")), "b".repeat(64))
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        val placement = ServicePlacement(ready)
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        every { store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
        every { blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
        every { worldAdapter.revalidate(any(), owner) } returns CompletableFuture.completedFuture(Unit)
        every { blueprints.pasteParts(snapshot, building, any()) } answers {
            arg<CivBuildBatch>(2) shouldBe CivBuildBatch(CivPosition(15, 64, 0), CivPosition(15, 64, 0))
            CompletableFuture.completedFuture(listOf(snapshot))
        }
        every { placement.operation.completion } returns placement.pasted
        every { blueprints.paste(snapshot, world.uid, building.anchor, any(), any()) } returns placement.operation
        world.loadChunk(0, 0); world.loadChunk(1, 0)
        return placement
    }
    fun placement(firstMaterial: Material = Material.OAK_PLANKS): ServicePlacement {
        val building = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, level = 1,
            anchor = CivPosition(15, 64, 0), bounds = CivBox(CivPosition(14, 63, -2), CivPosition(17, 68, 2)),
            entrance = CivPosition(15, 64, 3), phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), building.id, 1, 3, 0, ConstructionPhase.CONSTRUCTING,
            CivsRules.cost(building.type, 1), plannedBuilding = building)
        val reserved = CivsRules.reserveBuild(city, owner, building, order)
        val ready = CivsRules.validate(reserved.copy(construction = order.copy(workDone = 3, phase = ConstructionPhase.READY_TO_PLACE)))
        val plan = CivPlacementPlan(order.id, city.id, world.uid, building, listOf(
            CivPlanBlock(CivPosition(15, 64, 0), "minecraft:air", firstMaterial.key.toString()),
            CivPlanBlock(CivPosition(16, 64, 0), "minecraft:air", "minecraft:stone"),
        ), "b".repeat(64))
        val snapshot = mockk<ArcSchematicSnapshot>(relaxed = true)
        every { snapshot.blocks() } returns listOf(
            ArcSchematicBlock(ArcSchematicPoint(0, 0, 0), firstMaterial.key.toString(), false, false),
            ArcSchematicBlock(ArcSchematicPoint(1, 0, 0), "minecraft:stone", false, false),
        )
        val placement = ServicePlacement(ready)
        every { store.load() } returns CompletableFuture.completedFuture(listOf(ready))
        every { store.loadPlan(order.id) } returns CompletableFuture.completedFuture(plan)
        every { store.deletePlan(order.id) } returns CompletableFuture.completedFuture(true)
        every { store.deleteSchematic(order.id) } returns CompletableFuture.completedFuture(true)
        every { blueprints.recover(plan) } returns CompletableFuture.completedFuture(snapshot)
        every { worldAdapter.revalidate(plan, owner) } returns placement.validation
        every { placement.operation.completion } returns placement.pasted
        every { blueprints.paste(snapshot, world.uid, building.anchor, any(), any()) } returns placement.operation
        val repair = mockk<ArcSchematicOperation>(relaxed = true)
        every { repair.completion } returns placement.repaired
        every { blueprints.repair(any(), any()) } returns repair
        world.loadChunk(0, 0); world.loadChunk(1, 0)
        return placement
    }
    override fun close() { service.close(); tasks.close() }
}

private data class ServiceQueuedUpgrade(val city: Settlement, val building: CivBuilding, val design: CivPlacementPlan,
    val fresh: CivPlacementPlan, val capture: CompletableFuture<CivPlacementPlan>)

private class ServicePlacement(val city: Settlement) {
    val validation = CompletableFuture<Unit>()
    val pasted = CompletableFuture<ArcSchematicMutationResult>()
    val repaired = CompletableFuture<ArcSchematicMutationResult>()
    val operation = mockk<ArcSchematicOperation>(relaxed = true)
}
