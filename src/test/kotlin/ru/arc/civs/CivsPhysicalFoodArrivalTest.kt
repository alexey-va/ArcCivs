package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

class CivsPhysicalFoodArrivalTest : FunSpec({
    test("a hungry miner passes the building control to reach food in its actual second native holder") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            val quarry = h.assignQuarry()
            val control = CivPosition(0, 64, 0)
            val container = CivPosition(8, 64, 0)
            val hall = h.city.buildings.first().copy(control = control,
                bounds = CivBox(CivPosition(-2, 63, -2), CivPosition(10, 68, 2)))
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            h.world.getBlockAt(container.x, container.y, container.z).type = Material.BARREL
            val controlInventory = (h.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory
            val foodInventory = (h.world.getBlockAt(container.x, container.y, container.z).state as Barrel).inventory
            foodInventory.setItem(0, ItemStack(Material.BREAD, 4))
            val bag = List<CivResidentItem?>(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_PICKAXE", 1)
                CivsEquipment.BAG -> CivResidentItem("COBBLESTONE", 32)
                CivsEquipment.BAG + 1 -> CivResidentItem("COBBLESTONE", 64)
                else -> null
            } }
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                workers = h.city.workers.map { it.copy(hunger = 14, inventory = null, items = bag) }))
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(19, 64, 0), emptyList(),
                outputs = mapOf("COBBLESTONE" to 1L), durationMillis = 6_000, status = "digging-mine")
            val source = CivResidentFoodSource(hall.id, container, CivPosition(7, 64, 0))
            h.physicalFoodSource = source
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 1.5, 64.0, .5))
            citizenAtStorage(h.world, actor.location.point(), control) shouldBe true
            citizenCanReachBlock(actor, control) shouldBe true
            civsCanReachContainer(actor.location, container) shouldBe false
            val start = actor.location
            var pickups = 0
            val pending = CompletableFuture<Settlement>()
            h.physicalFoodPickup = { selected ->
                selected shouldBe source
                civsCanReachContainer(actor.location, selected.container) shouldBe true
                pickups++
                pending
            }
            h.owner.pulse(2_000)
            pickups shouldBe 0
            for (tick in 1..600) {
                h.navigate()
                if (!civsCanReachContainer(actor.location, container)) pickups shouldBe 0
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (pickups > 0) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { pickups shouldBe 1 }
            (actor.location.distance(start) > 2.0) shouldBe true
            civsCanReachContainer(actor.location, container) shouldBe true
            repeat(40) { tick ->
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
            }
            pickups shouldBe 1
            // Arrival permits one pending request; its receipt still owns every item mutation.
            controlInventory.isEmpty shouldBe true
            foodInventory.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
            h.city.workers.single().items shouldBe bag
            h.city.workers.single().hunger shouldBe 14
            h.supplyRequests shouldBe 0
            h.deliveries shouldBe 0
        }
    }

    test("the existing virtual food trip still reaches its building control before requesting supplies") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            val quarry = h.assignQuarry()
            val control = CivPosition(0, 64, 0)
            val hall = h.city.buildings.first().copy(control = control)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                inventory = CivInventory(CivStorageMode.VIRTUAL),
                workers = h.city.workers.map { it.copy(hunger = 14, inventory = null,
                    items = List(CivsEquipment.SLOTS) { null }) }))
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(19, 64, 0), emptyList(),
                outputs = mapOf("COBBLESTONE" to 1L), durationMillis = 6_000, status = "digging-mine")
            h.foodSource = hall.id
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 8.5, 64.0, .5))
            val start = actor.location
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 0
            for (tick in 1..600) {
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (h.supplyRequests > 0) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { h.supplyRequests shouldBe 1 }
            (actor.location.distance(start) > 2.0) shouldBe true
            citizenAtStorage(h.world, actor.location.point(), control) shouldBe true
            citizenCanReachBlock(actor, control) shouldBe true
            CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 0
            h.deliveries shouldBe 0
        }
    }
})
