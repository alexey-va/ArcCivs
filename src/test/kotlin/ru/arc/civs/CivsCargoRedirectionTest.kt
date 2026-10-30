package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.Barrel
import org.bukkit.block.data.type.Barrel as BarrelData
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsCargoRedirectionTest : FunSpec({
    lateinit var h: CargoRedirectionFixture
    beforeEach { h = CargoRedirectionFixture() }
    afterEach { h.close() }

    test("redirection retains the accepted identity bag and worker token across the actual city store") {
        val original = h.city
        val cargo = h.cargo
        val root = Files.createTempDirectory("cargo-redirect-")
        try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.save(original).join()
                h.redirect()
                val redirected = h.city
                val carried = requireNotNull(redirected.workers.single().cargo)
                carried shouldBe cargo.copy(deliveryBuildingId = h.alternative.id)
                carried.id shouldBe cargo.id
                carried.targetBuildingId shouldBe cargo.targetBuildingId
                redirected.workers.single().copy(cargo = cargo) shouldBe original.workers.single()
                store.save(redirected).join()
            }
            CivsStore(root, Executor { it.run() }).use { store ->
                val restored = store.load().join().single()
                restored shouldBe h.city
                CivsLogistics.destination(restored, requireNotNull(restored.workers.single().cargo))?.id shouldBe h.alternative.id
                CivsEquipment.plainCount(restored.workers.single(), "TORCH") shouldBe 1
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("a full target chooses only a permitted active reachable city depot for the complete batch") {
        h.fill(h.target.id)
        val from = requireNotNull(h.target.control).copy(z = 1)
        val selected = CivsLogistics.redirectDestination(h.city, h.cargo, h.target.id, from) { id, material ->
            h.storage.redirectCargoRoom(h.city, id, material)
        }
        selected?.id shouldBe h.alternative.id
        h.denied += requireNotNull(h.alternative.control)
        h.fill(h.hall.id)
        CivsLogistics.redirectDestination(h.city, h.cargo, h.target.id, from) { id, material ->
            h.storage.redirectCargoRoom(h.city, id, material)
        } shouldBe null
        h.storage.redirectCargoRoom(h.city, h.alternative.id, "TORCH") shouldBe 0
        val unavailable = h.alternative.copy(control = CivPosition(200, 64, 0))
        val changed = h.city.copy(buildings = h.city.buildings.map { if (it.id == unavailable.id) unavailable else it })
        CivsLogistics.redirectDestination(changed, h.cargo, h.target.id, from) { _, _ -> 64 }?.id shouldBe h.hall.id
        CivsLogistics.redirectDestination(h.city, h.cargo.copy(amount = 2), h.target.id, from) { _, _ -> 1 } shouldBe null
    }

    test("an address proof cannot redirect unpicked abstract or non-depot cargo") {
        val before = h.city
        shouldThrow<CivsRejection> { CivsLogistics.redirect(before, h.workerId,
            h.cargo.copy(inventoryBacked = false), h.alternative.id) }.reason shouldBe "stale-delivery"
        shouldThrow<CivsRejection> { CivsLogistics.redirect(before, h.workerId, h.cargo, h.source.id) }
            .reason shouldBe "stale-delivery"
        CivsLogistics.validCargo(before, h.cargo.copy(inventoryBacked = false, deliveryBuildingId = h.alternative.id)) shouldBe false
        h.city shouldBe before
    }

    test("no native credit can be prepared without a fresh actual-arrival callback") {
        h.redirect()
        val before = h.city
        val id = UUID.randomUUID()
        h.storage.reserve(before, id)
        h.arrived = false
        shouldThrow<CivsRejection> {
            h.storage.prepareCarriedCargoDropoff(before, h.workerId, h.cargo, h.alternative.id, h.time, h::requireArrival)
        }.reason shouldBe "route-unreachable"
        h.arrivals shouldBe 1
        h.inventory(h.alternative.id).isEmpty shouldBe true
        h.city shouldBe before
        h.storage.unlock(id)
        h.arrived = true
        val tx = h.prepare()
        h.arrivals shouldBe 3
        tx.cargoDropoff!!.workerBefore.cargo shouldBe h.cargo
        h.inventory(h.alternative.id).isEmpty shouldBe true
    }

    test("typed cargo receipts reject a foreign holder changed quantity or an unconsumed bag") {
        h.redirect()
        val tx = h.prepare()
        shouldThrow<IllegalArgumentException> { tx.copy(changes = tx.changes.map {
            it.copy(after = requireNotNull(it.after).copy(amount = 2))
        }).validated() }
        shouldThrow<IllegalArgumentException> { tx.copy(after = tx.after.copy(workers = listOf(h.worker))).validated() }
        val foreign = requireNotNull(h.hall.control)
        shouldThrow<IllegalArgumentException> { tx.copy(changes = tx.changes.map { it.copy(container = foreign) },
            cargoDropoff = tx.cargoDropoff!!.copy(containers = listOf(CivCargoContainerState(foreign, h.world.getBlockAt(foreign.x, foreign.y, foreign.z).blockData.asString))))
            .validated() }
        h.inventory(h.alternative.id).isEmpty shouldBe true
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 1
    }

    test("container type or pending slot substitution after planning prevents every credit") {
        h.redirect()
        val tx = h.prepare()
        val point = requireNotNull(h.alternative.control)
        h.inventory(h.alternative.id).setItem(0, ItemStack(Material.DIAMOND, 1))
        (h.storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.DIAMOND, 1)
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 1
        h.inventory(h.alternative.id).clear()
        h.world.getBlockAt(point.x, point.y, point.z).type = Material.CHEST
        (h.storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 1
    }

    test("closing the same barrel during journal acknowledgement preserves its exact cargo delivery") {
        h.redirect()
        val point = requireNotNull(h.alternative.control)
        val block = h.world.getBlockAt(point.x, point.y, point.z)
        block.blockData = (block.blockData as BarrelData).clone().also { (it as BarrelData).isOpen = true }
        val tx = h.prepare()
        val captured = tx.cargoDropoff!!.containers.single().blockData
        val accepted = CompletableFuture<CivInventoryTransaction>()
        every { h.store.saveInventoryTransaction(any()) } returns accepted
        val result = h.journal.submit(tx)
        block.blockData = (block.blockData as BarrelData).clone().also { (it as BarrelData).isOpen = false }
        accepted.complete(tx); h.ticks()
        result.join() shouldBe tx.after
        h.city shouldBe tx.after
        h.worker.cargo shouldBe null
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        (block.blockData as BarrelData).isOpen shouldBe false // Matching must never rewrite native viewer state.
        tx.cargoDropoff!!.containers.single().blockData shouldBe captured
        h.journal.pendingCount shouldBe 0
        h.journal.recover(); h.ticks()
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
    }

    test("a restored pending cargo receipt accepts its now closed barrel and replays the batch once") {
        h.redirect()
        val point = requireNotNull(h.alternative.control)
        val block = h.world.getBlockAt(point.x, point.y, point.z)
        block.blockData = (block.blockData as BarrelData).clone().also { (it as BarrelData).isOpen = true }
        val tx = h.prepare()
        val accepted = CompletableFuture<CivInventoryTransaction>()
        every { h.store.saveInventoryTransaction(any()) } returns accepted
        val before = h.city
        val result = h.journal.submit(tx)
        accepted.completeExceptionally(IOException("journal acknowledgement was unknown")); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.inventory(h.alternative.id).isEmpty shouldBe true
        block.blockData = (block.blockData as BarrelData).clone().also { (it as BarrelData).isOpen = false }
        val restored = Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated()
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(restored))
        h.journal.bootstrap(); h.ticks()
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.worker.cargo shouldBe null
        h.worker.completedCycles shouldBe before.workers.single().completedCycles + 1
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        h.journal.pendingCount shouldBe 0
        h.journal.recover(); h.ticks()
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
    }

    test("an open flag change never admits a changed facing slot before-image or lost Lands authority") {
        h.redirect()
        val tx = h.prepare()
        val point = requireNotNull(h.alternative.control)
        val block = h.world.getBlockAt(point.x, point.y, point.z)
        val captured = Bukkit.createBlockData(tx.cargoDropoff!!.containers.single().blockData) as BarrelData
        val actual = captured.clone() as BarrelData
        actual.isOpen = !captured.isOpen
        actual.facing = if (captured.facing == BlockFace.NORTH) BlockFace.SOUTH else BlockFace.NORTH
        block.blockData = actual
        (h.storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        h.inventory(h.alternative.id).isEmpty shouldBe true
        actual.facing = captured.facing
        block.blockData = actual
        h.inventory(h.alternative.id).setItem(0, ItemStack(Material.DIAMOND, 1))
        (h.storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.DIAMOND, 1)
        h.inventory(h.alternative.id).clear()
        h.denied += point
        (h.storage.reconcile(tx) is CivInventoryApplyResult.Retry) shouldBe true
        h.inventory(h.alternative.id).isEmpty shouldBe true
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 1
        h.worker.cargo shouldBe tx.cargoDropoff!!.workerBefore.cargo
    }

    test("claim loss after journal acceptance retains the exact cargo receipt until authority returns") {
        h.redirect()
        val tx = h.prepare()
        val accepted = CompletableFuture<CivInventoryTransaction>()
        every { h.store.saveInventoryTransaction(any()) } returns accepted
        val before = h.city
        val result = h.journal.submit(tx)
        h.denied += requireNotNull(h.alternative.control)
        accepted.complete(tx); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.inventory(h.alternative.id).isEmpty shouldBe true
        h.journal.pendingCount shouldBe 1
        verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        h.denied.clear(); h.time += 5_000
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        h.worker.cargo shouldBe null
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("an unknown journal acknowledgement recovers the same batch from the durable receipt exactly once") {
        h.redirect()
        val tx = h.prepare()
        val accepted = CompletableFuture<CivInventoryTransaction>()
        every { h.store.saveInventoryTransaction(any()) } returns accepted
        val before = h.city
        val result = h.journal.submit(tx)
        accepted.completeExceptionally(IOException("journal rename outcome was unknown")); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.inventory(h.alternative.id).isEmpty shouldBe true
        h.journal.pendingCount shouldBe 1
        val restored = Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated()
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(restored))
        h.journal.bootstrap(); h.ticks()
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        h.worker.completedCycles shouldBe before.workers.single().completedCycles + 1
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        h.journal.recover(); h.ticks()
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
    }

    test("native persistence acknowledgement fences city debit and replay does not duplicate a written slot") {
        h.redirect()
        val tx = h.prepare()
        val persisted = CompletableFuture<Unit>()
        h.persistence = { _, _ -> persisted }
        val before = h.city
        val result = h.journal.submit(tx); h.ticks()
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        h.city shouldBe before
        h.journal.pendingCount shouldBe 1
        result.isDone shouldBe false
        verify(exactly = 0) { h.store.save(tx.after) }
        persisted.completeExceptionally(CivsNativePersistenceRetry("region acknowledgement was unknown")); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.persistence = { _, _ -> CompletableFuture.completedFuture(Unit) }
        h.denied += requireNotNull(h.alternative.control) // Already accepted after-state needs no second credit.
        h.time += 5_000; h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.worker.cargo shouldBe null
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
        h.worker.completedCycles shouldBe before.workers.single().completedCycles + 1
        h.journal.pendingCount shouldBe 0
    }

    test("typed receipts roundtrip through the actual store and historical cargo receipts retain generic recovery") {
        h.redirect()
        val tx = h.prepare()
        val oldDocument = Gson().toJsonTree(tx).asJsonObject.also { it.remove("cargoDropoff") }
        val historical = Gson().fromJson(oldDocument, CivInventoryTransaction::class.java).validated()
        val root = Files.createTempDirectory("cargo-journal-")
        try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.saveInventoryTransaction(tx).join() shouldBe tx
                store.loadInventoryTransactions().join() shouldBe listOf(tx)
                store.saveInventoryTransaction(tx).join() shouldBe tx
                shouldThrow<CompletionException> { store.saveInventoryTransaction(tx.copy(
                    cargoDropoff = tx.cargoDropoff!!.copy(acceptedAt = tx.cargoDropoff!!.acceptedAt + 1))).join() }
                store.deleteInventoryTransaction(tx.id).join()
                store.saveInventoryTransaction(historical).join() shouldBe historical
                store.loadInventoryTransactions().join() shouldBe listOf(historical)
            }
        } finally { root.toFile().deleteRecursively() }
        historical.cargoDropoff shouldBe null
        h.storage.unlock(tx.id)
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(historical))
        h.persistence = { _, _ -> error("Historical generic receipts retain their original recovery contract") }
        h.journal.bootstrap(); h.ticks(); h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 1)
    }

    test("native inventory clicks invalidate capacity observations so a freed slot can wake a waiting shipment") {
        val snapshot = h.storage.supplyRevision(h.city.id)
        val admin = h.paper.addPlayer("CargoAdmin")
        val view = admin.openInventory(h.inventory(h.alternative.id))!!
        h.storage.supplyClick(org.bukkit.event.inventory.InventoryClickEvent(view,
            org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
            org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL))
        (h.storage.supplyRevision(h.city.id) > snapshot) shouldBe true
        admin.closeInventory()
    }

    test("a no-alternative cargo retry waits thirty seconds while ordinary delivery keeps its five seconds") {
        val token = CitizenToken(UUID.randomUUID(), 0, 0, null, UUID.randomUUID())
        fun ready(): CitizenCycle = CitizenCycle(token, 0, pickupAtWorkplace = true, carrying = true).also {
            it.start(); it.arrive(1_000); requireNotNull(it.beginDelivery(1_000))
        }
        val full = ready()
        full.failDelivery("storage-full", 1_000, 30_000)
        full.retry(30_999); full.phase shouldBe CitizenPhase.PAUSED
        full.retry(31_000); full.phase shouldBe CitizenPhase.READY
        val ordinary = ready()
        ordinary.failDelivery("storage-full", 1_000)
        ordinary.retry(5_999); ordinary.phase shouldBe CitizenPhase.PAUSED
        ordinary.retry(6_000); ordinary.phase shouldBe CitizenPhase.READY
    }
})

internal class CargoRedirectionFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = CivsWorksiteWorldFixture("cargo-redirection").also(paper.server::addWorld)
    private val plugin = paper.createSimplePlugin("CargoRedirectionTest")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val storage = CivsStorage(plugin) { emptyArray() }
    val hall = building(0, BuildingType.TOWN_HALL)
    val source = building(8, BuildingType.FORESTER)
    val target = building(16, BuildingType.WAREHOUSE)
    val alternative = building(24, BuildingType.WAREHOUSE)
    val workerId = UUID.randomUUID()
    private val cities = mutableMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value; storage.updateCity(value) }
    val worker get() = city.workers.single()
    val cargo get() = requireNotNull(worker.cargo)
    val store = mockk<CivsStore>()
    val busy = mutableSetOf<UUID>()
    val unhealthy = mutableSetOf<UUID>()
    val denied = mutableSetOf<CivPosition>()
    var arrived = true
    var arrivals = 0
    var time = 10_000L
    var persistence: (CivInventoryTransaction, Boolean) -> CompletableFuture<Unit> = { _, _ -> CompletableFuture.completedFuture(Unit) }
    val journal: CivsInventoryJournal
    init {
        for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
        listOf(hall, source, target, alternative).forEach { building ->
            val point = requireNotNull(building.control)
            world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
        }
        val cityId = UUID.randomUUID()
        val cargo = CivCargo(CivsLogistics.shipmentId(cityId, workerId, 0, source.id, target.id, "TORCH"),
            target.id, source.id, target.id, source.entrance, "TORCH", 1, inventoryBacked = true)
        val courier = CivsEquipment.collectSupplies(CivWorker(workerId, "Courier", target.id, nextDeliveryAt = 1),
            mapOf("TORCH" to 1L)).copy(cargo = cargo)
        city = CivsRules.validate(Settlement(cityId, UUID.randomUUID(), "Cargo", world.uid, CivPosition(0, 64, 0), 64,
            "nordic", Stock(), listOf(hall, source, target, alternative), listOf(courier),
            inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
        storage.foodCanModify = { _, point -> point !in denied }
        storage.persistCollectedNative = { tx, before -> persistence(tx, before) }
        every { store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("Unexpected cargo materialization") }, {}, { error("No new collection receipt") },
            { _, _, _ -> null }, {}, { tx, _ -> cities.getValue(tx.cityId) }, {}, { time })
    }
    private fun building(x: Int, type: BuildingType): CivBuilding {
        val point = CivPosition(x, 64, 0)
        return CivBuilding(UUID.randomUUID(), type, 1, point, 0, "test/$type",
            CivBox(point.copy(x = x - 2, z = -2), point.copy(x = x + 2, y = 68, z = 2)),
            point.copy(z = -2), BuildingPhase.ACTIVE, point)
    }
    fun inventory(id: UUID) = requireNotNull(city.building(id)?.control).let {
        (world.getBlockAt(it.x, it.y, it.z).state as Barrel).inventory
    }
    fun fill(id: UUID) { repeat(27) { inventory(id).setItem(it, ItemStack(Material.STONE, 64)) } }
    fun redirect() { city = CivsLogistics.redirect(city, workerId, cargo, alternative.id) }
    fun requireArrival() { arrivals++; if (!arrived) throw CivsRejection("route-unreachable") }
    fun prepare(): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val destination = requireNotNull(CivsLogistics.destination(city, cargo))
        val prepared = storage.prepareCarriedCargoDropoff(city, workerId, cargo, destination.id, time, ::requireArrival)
        return CivInventoryTransaction(id, city.id, city.revision,
            CivsLogistics.unload(city, workerId, cargo.id, time).copy(inventory = prepared.inventoryAfter),
            prepared.changes, cargoDropoff = prepared.cargoDropoff).also { storage.seal(it) }
    }
    fun ticks() { paper.performTicks(8) }
    override fun close() { tasks.close(); storage.close(); paper.close() }
}
