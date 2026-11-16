package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsWarehouseRedistributionReceiptTest : FunSpec({
    lateinit var h: CargoRedirectionFixture
    beforeEach {
        h = CargoRedirectionFixture()
        h.city = h.city.copy(workers = listOf(h.worker.copy(cargo = null, items = null, inventory = null)))
        h.fill(h.target.id)
        h.inventory(h.target.id).setItem(0, ItemStack(Material.TORCH, 10))
    }
    afterEach { h.close() }

    test("a partial native stack releases its actual source slot and reaches another depot exactly once") {
        val cargo = warehouseCargo(h)
        val before = h.city
        val tx = warehousePickup(h, cargo)
        tx.changes.single().before shouldBe CivPlainItem("TORCH", 10)
        tx.changes.single().after shouldBe null
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        val picked = h.journal.submitCargoPickup(before, tx) { h.requireArrival() }
        h.ticks(); picked.join() shouldBe tx.after
        h.inventory(h.target.id).getItem(0) shouldBe null
        h.inventory(h.target.id).getItem(1) shouldBe ItemStack(Material.STONE, 64)
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 10
        h.worker.cargo shouldBe cargo.copy(inventoryBacked = true)
        h.worker.completedCycles shouldBe 0L
        CivsLogistics.pickup(h.city, h.workerId, cargo) shouldBe h.city
        val paid = h.worker.cargo
        val delivered = h.prepare()
        val dropped = h.journal.submit(delivered); h.ticks(); dropped.join() shouldBe delivered.after
        h.worker.cargo shouldBe null
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        h.inventory(h.target.id).getItem(0) shouldBe null
        h.worker.completedCycles shouldBe 1L
        h.journal.recover(); h.ticks()
        h.inventory(h.alternative.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        paid!!.id shouldBe cargo.id
    }

    test("a debit that would only shrink a partial source stack is rejected without native writes") {
        val cargo = warehouseCargo(h, 5)
        val id = UUID.randomUUID()
        h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareRedistributionPickup(h.city, h.workerId, cargo, emptyList(), h::requireArrival) }
            .reason shouldBe "stale-delivery"
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        h.worker.cargo shouldBe null
        h.storage.unlock(id)
    }

    test("real target space includes already-paid and selected competing cargo") {
        h.fill(h.alternative.id)
        h.inventory(h.alternative.id).setItem(0, null); h.inventory(h.alternative.id).setItem(1, null)
        val cargo = warehouseCargo(h)
        val other = cargo.copy(id = UUID.randomUUID(), amount = 64, redistribution = false, inventoryBacked = true)
        val paid = CivCourierPlan(other, h.alternative.id, h.alternative.entrance, true)
        val id = UUID.randomUUID(); h.storage.reserve(h.city, id)
        shouldThrow<CivsRejection> { h.storage.prepareRedistributionPickup(h.city, h.workerId, cargo, listOf(paid), h::requireArrival) }
            .reason shouldBe "storage-full"
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        h.inventory(h.alternative.id).getItem(0) shouldBe null
        h.storage.unlock(id)
    }

    test("pre-admission source checks retain the city claim and reject an actor who leaves") {
        val cargo = warehouseCargo(h)
        val before = h.city
        val tx = warehousePickup(h, cargo)
        val result = h.journal.submitCargoPickup(before, tx) {
            (before.id in h.busy) shouldBe true
            h.storage.hasClaim(before) shouldBe true
            h.requireArrival()
        }
        (before.id in h.busy) shouldBe true
        shouldThrow<CivsRejection> { h.storage.reserve(before, UUID.randomUUID()) }.reason shouldBe "storage-busy"
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        h.arrived = false
        h.ticks()
        (shouldThrow<CompletionException> { result.join() }.cause as CivsRejection).reason shouldBe "route-unreachable"
        h.city shouldBe before
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        (before.id in h.busy) shouldBe false
        h.storage.hasClaim(before) shouldBe false
        h.nativePersistenceCalls shouldBe 0
        h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
    }

    test("a filled destination before admission cancels the untouched pickup") {
        val cargo = warehouseCargo(h)
        val before = h.city
        val tx = warehousePickup(h, cargo)
        val result = h.journal.submitCargoPickup(before, tx) {
            h.requireArrival()
            h.storage.requireRedistributionCapacity(before, cargo, emptyList())
        }
        h.fill(h.alternative.id)
        h.ticks()
        (shouldThrow<CompletionException> { result.join() }.cause as CivsRejection).reason shouldBe "stale-delivery"
        h.nativePersistenceCalls shouldBe 0
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
    }

    test("RAM completion gates bag credit after the exact native debit") {
        val cargo = warehouseCargo(h)
        val before = h.city
        val tx = warehousePickup(h, cargo)
        val completion = CompletableFuture<Settlement>()
        every { h.store.completeInventoryTransaction(tx) } returns completion
        val result = h.journal.submitCargoPickup(before, tx) { h.requireArrival() }; h.ticks()
        h.inventory(h.target.id).getItem(0) shouldBe null
        h.city shouldBe before
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        result.isDone shouldBe false
        h.storage.hasClaim(before) shouldBe true
        h.nativePersistenceCalls shouldBe 0
        verify(exactly = 0) { h.store.deleteInventoryTransaction(tx.id) }
        h.arrived = false // Accepted intent keeps its source proof after the actor leaves.
        completion.complete(tx.after); h.ticks()
        result.join() shouldBe tx.after
        h.city shouldBe tx.after
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 10
        h.worker.cargo shouldBe cargo.copy(inventoryBacked = true)
        h.inventory(h.target.id).getItem(0) shouldBe null
        h.worker.completedCycles shouldBe 0L
        h.journal.recover(); h.ticks()
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 10
        h.storage.hasClaim(before) shouldBe false
        h.nativePersistenceCalls shouldBe 0
        verify(exactly = 1) { h.store.completeInventoryTransaction(tx) }
    }

    test("legacy pickup recovery retries an unknown native acknowledgement without an actor or duplicate debit") {
        val cargo = warehouseCargo(h)
        val before = h.city
        val tx = warehousePickup(h, cargo)
        val root = Files.createTempDirectory("warehouse-pickup-")
        val stored = try {
            CivsStore(root, Executor { it.run() }).use { it.save(before).join() }
            writeLegacyInventoryReceipt(root, tx)
            CivsStore(root, Executor { it.run() }).use { it.loadInventoryTransactions().join().single() }
        } finally { root.toFile().deleteRecursively() }
        stored shouldBe tx
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(stored))
        val nativeAck = CompletableFuture<Unit>()
        h.persistence = { _, source -> source shouldBe false; nativeAck }
        h.arrived = false
        val boot = h.journal.bootstrap(); h.ticks(); boot.join()
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        h.journal.recover(); h.ticks()
        h.inventory(h.target.id).getItem(0) shouldBe null
        h.city shouldBe before
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 0
        h.nativePersistenceCalls shouldBe 1
        nativeAck.completeExceptionally(CivsNativePersistenceRetry("warehouse source region acknowledgement is unknown")); h.ticks()
        h.journal.pendingCount shouldBe 1
        h.journal.blockedCount shouldBe 0
        h.persistence = { _, _ -> CompletableFuture.completedFuture(Unit) }
        h.time += 5_000; h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.inventory(h.target.id).getItem(0) shouldBe null
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 10
        h.journal.recover(); h.ticks()
        CivsEquipment.plainCount(h.worker, "TORCH") shouldBe 10
        h.nativePersistenceCalls shouldBe 2
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
    }

    test("the typed source proof rejects foreign holders extra credit and unsupported historical marker assumptions") {
        val cargo = warehouseCargo(h)
        val tx = warehousePickup(h, cargo)
        val foreign = requireNotNull(h.hall.control)
        shouldThrow<IllegalArgumentException> { tx.copy(changes = tx.changes.map { it.copy(container = foreign) },
            cargoPickup = tx.cargoPickup!!.copy(containers = listOf(CivCargoContainerState(foreign,
                h.world.getBlockAt(foreign.x, foreign.y, foreign.z).blockData.asString)))).validated() }
        shouldThrow<IllegalArgumentException> { tx.copy(cargoPickup = tx.cargoPickup!!.copy(cargo = cargo.copy(amount = 9))).validated() }
        val old = Gson().toJsonTree(cargo.copy(redistribution = false)).asJsonObject.also { it.remove("redistribution") }
        Gson().fromJson(old, CivCargo::class.java).redistribution shouldBe false
        h.inventory(h.target.id).getItem(0) shouldBe ItemStack(Material.TORCH, 10)
        h.worker.cargo shouldBe null
    }
})

private fun warehouseCargo(h: CargoRedirectionFixture, amount: Long = 10): CivCargo =
    CivCargo(CivsLogistics.shipmentId(h.city.id, h.workerId, h.worker.completedCycles, h.target.id, h.alternative.id, "TORCH"),
        h.target.id, h.target.id, h.alternative.id, h.target.entrance, "TORCH", amount, redistribution = true)

private fun warehousePickup(h: CargoRedirectionFixture, cargo: CivCargo): CivInventoryTransaction {
    val id = UUID.randomUUID()
    h.storage.reserve(h.city, id)
    val prepared = h.storage.prepareRedistributionPickup(h.city, h.workerId, cargo, emptyList(), h::requireArrival)
    val after = CivsLogistics.pickup(h.city, h.workerId, cargo).copy(inventory = prepared.inventoryAfter)
    return CivInventoryTransaction(id, h.city.id, h.city.revision, after, prepared.changes, cargoPickup = prepared.cargoPickup)
        .also { h.storage.seal(it) }
}
