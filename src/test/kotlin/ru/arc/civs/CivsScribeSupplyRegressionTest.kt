package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack

class CivsScribeSupplyRegressionTest : FunSpec({
    for (bread in listOf(0, 8)) test("a hungry physical scribe collects work inputs with $bread bread in the hall") {
        localInventoryScenario { h, disk ->
            val hall = h.city.buildings.first().copy(control = CivPosition(0, 63, 0))
            val bakery = h.city.buildings.last().copy(type = BuildingType.BAKERY, control = CivPosition(10, 63, 0),
                schematicId = CivsBlueprints.schematicId("nordic", BuildingType.BAKERY, 1))
            val worker = h.city.workers.first().copy(assignment = hall.id, nextDeliveryAt = 1, hunger = 10)
            val baker = h.city.workers[1].copy(assignment = bakery.id, nextDeliveryAt = 1)
            val city = CivsRules.validate(h.city.copy(buildings = listOf(hall, bakery), workers = listOf(worker, baker),
                stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            val barrel = h.world.getBlockAt(0, 63, 0).also { it.type = Material.BARREL }.state as Barrel
            barrel.inventory.setItem(0, ItemStack(Material.PAPER, 6))
            barrel.inventory.setItem(1, ItemStack(Material.LEATHER, 2))
            if (bread > 0) barrel.inventory.setItem(2, ItemStack(Material.BREAD, bread))
            val bakeryBarrel = h.world.getBlockAt(10, 63, 0).also { it.type = Material.BARREL }.state as Barrel
            bakeryBarrel.inventory.setItem(0, ItemStack(Material.WHEAT, 6))
            disk.store.save(city).join()
            h.start()
            h.service.residentSupplySource(city, worker) shouldBe null

            val checkpoint = disk.holdCheckpoint()
            val supplied = h.await(h.service.prepareResidentSupplies(city.id, worker.id, hall.id))
            supplied.revision shouldBe city.revision + 1
            val carrying = supplied.workers.first()
            CivsEquipment.plainCount(carrying, "PAPER") shouldBe 3
            CivsEquipment.plainCount(carrying, "LEATHER") shouldBe 1
            CivsEquipment.plainCount(carrying, "BREAD") shouldBe 0
            barrel.inventory.getItem(0)!!.amount shouldBe 3
            barrel.inventory.getItem(1)!!.amount shouldBe 1
            (barrel.inventory.getItem(2)?.amount ?: 0) shouldBe bread
            bakeryBarrel.inventory.getItem(0) shouldBe ItemStack(Material.WHEAT, 6)
            h.service.workInputsReady(supplied, carrying) shouldBe true
            carrying.hunger shouldBe worker.hunger
            carrying.completedCycles shouldBe worker.completedCycles
            supplied.stock shouldBe city.stock
            verify(atLeast = 1) { h.citizens.requireStorageArrival(city.id, worker.id, hall.id) }
            h.await(h.service.prepareResidentSupplies(city.id, worker.id, hall.id)) shouldBe supplied

            val baked = h.await(h.service.prepareResidentSupplies(city.id, baker.id, bakery.id))
            baked.revision shouldBe city.revision + 2
            baked.workers.first() shouldBe carrying
            CivsEquipment.plainCount(baked.workers.last(), "WHEAT") shouldBe 3
            bakeryBarrel.inventory.getItem(0) shouldBe ItemStack(Material.WHEAT, 3)
            checkpoint.isDone shouldBe false

            disk.resume()
            checkpoint.join()
            disk.checkpointed() shouldBe baked
        }
    }
})
