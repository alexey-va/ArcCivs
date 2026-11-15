package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

class CivsScribeSupplyRegressionTest : FunSpec({
    for (bread in listOf(0, 8)) test("a hungry physical scribe collects work inputs with $bread bread in the hall") {
        serviceScenario { h ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val worker = h.city.workers.first().copy(assignment = hall.id, nextDeliveryAt = 1, hunger = 10)
            val city = CivsRules.validate(h.city.copy(buildings = listOf(hall), workers = listOf(worker),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            val barrel = h.world.getBlockAt(0, 63, 0).also { it.type = Material.BARREL }.state as Barrel
            barrel.inventory.setItem(0, ItemStack(Material.PAPER, 6))
            barrel.inventory.setItem(1, ItemStack(Material.LEATHER, 2))
            if (bread > 0) barrel.inventory.setItem(2, ItemStack(Material.BREAD, bread))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            val journal = CompletableFuture<CivInventoryTransaction>()
            val receipts = mutableListOf<CivInventoryTransaction>()
            every { h.store.saveInventoryTransaction(any()) } answers {
                firstArg<CivInventoryTransaction>().also(receipts::add)
                journal
            }
            every { h.store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
            h.start()
            h.service.residentSupplySource(city, worker) shouldBe null
            val pickup = h.service.prepareResidentSupplies(city.id, worker.id, hall.id)
            h.tickUntil("scribe input pickup journal") { receipts.isNotEmpty() }
            receipts.single().changes.map { it.before?.material }.toSet() shouldBe setOf("PAPER", "LEATHER")
            barrel.inventory.getItem(0)!!.amount shouldBe 6
            barrel.inventory.getItem(1)!!.amount shouldBe 2
            h.service.settlements().single().workers.single() shouldBe worker
            journal.complete(receipts.single())
            h.tickUntil("scribe input pickup save") { h.saves.isNotEmpty() }
            h.commit()
            val supplied = pickup.join()
            val carrying = supplied.workers.single()
            CivsEquipment.plainCount(carrying, "PAPER") shouldBe 3L
            CivsEquipment.plainCount(carrying, "LEATHER") shouldBe 1L
            CivsEquipment.plainCount(carrying, "BREAD") shouldBe 0L
            barrel.inventory.getItem(0)!!.amount shouldBe 3
            barrel.inventory.getItem(1)!!.amount shouldBe 1
            (barrel.inventory.getItem(2)?.amount ?: 0) shouldBe bread
            h.service.workInputsReady(supplied, carrying) shouldBe true
            carrying.hunger shouldBe worker.hunger
            carrying.completedCycles shouldBe worker.completedCycles
            supplied.stock shouldBe city.stock
            verify(atLeast = 1) { h.citizens.requireStorageArrival(city.id, worker.id, hall.id) }
            h.await(h.service.prepareResidentSupplies(city.id, worker.id, hall.id)) shouldBe supplied
            receipts.size shouldBe 1
        }
    }
})
