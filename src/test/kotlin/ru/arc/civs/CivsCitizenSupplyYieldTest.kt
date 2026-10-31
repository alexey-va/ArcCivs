package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenSupplyYieldTest : FunSpec({
    test("a pending stationary builder is bypassed by a real detour and its courier delivers the original seven items") {
        residentRuntime(workerId = UUID.fromString("f2cd0cc5-a317-4985-bfbc-1fbbcd2a181d")) { h ->
            val (farm, courierId) = h.courierSource("GRAVEL", 7)
            val hut = farm.copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val warehouse = h.city.buildings.first { it.type == BuildingType.WAREHOUSE }
            val house = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                anchor = CivPosition(30, 64, 0), entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)), phase = BuildingPhase.CONSTRUCTING)
            val order = CivConstruction(UUID.randomUUID(), house.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = house, batches = CivConstructionBatches(2, 16),
                materials = mapOf("GRAVEL" to 3L), payment = CivConstructionPayment(awaiting = mapOf("GRAVEL" to 3L)), workCredit = 1)
            val cargo = CivCargo(UUID.fromString("02451cdf-18c4-3e7a-b700-0170f7c86a45"), warehouse.id, warehouse.id,
                hut.id, warehouse.entrance, "GRAVEL", 7, inventoryBacked = true)
            val courierPlan = CivCourierPlan(cargo, hut.id, hut.entrance, true)
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(),
                buildings = listOf(h.city.buildings.first(), hut, warehouse, house), construction = order,
                workers = h.city.workers.map { worker ->
                    if (worker.id == h.workerId) worker.copy(items = null)
                    else CivsEquipment.collectSupplies(worker.copy(cargo = cargo), mapOf("GRAVEL" to 7L))
                }))
            h.courierProvider = { city, worker -> courierPlan.takeIf {
                worker.id == courierId && city.workers.first { it.id == courierId }.cargo == cargo
            } }
            h.workReady = false
            h.holdNavigation = true
            h.navigationNow = 2_000
            val builderNpc = h.owner.collisionActors().single()
            // The two feet offsets are the Auto2 2026-10-31 stall, translated to this fixture barrel.
            builderNpc.teleport(Location(h.world, 21.7922244105316, 64.0, -.5))
            h.owner.pulse(2_000)
            val courierNpc = h.owner.collisionActors().single { it.uniqueId == courierId }
            courierNpc.teleport(Location(h.world, 22.4146290781107, 64.0, -.299603741313))
            val builder = h.owner.javaClass.getDeclaredField("actors").run {
                isAccessible = true
                requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
            }
            val courierActor = h.owner.javaClass.getDeclaredField("actors").run {
                isAccessible = true
                requireNotNull((get(h.owner) as Map<*, *>)[courierId]) as CitizenActor
            }
            // Complete fixture placement before the tested route starts; its original cargo and cycle stay accepted.
            courierActor.route?.motion?.cancel()
            courierActor.route = null
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 1
            (builder.supplyPickup != null) shouldBe true
            val pendingFeet = builderNpc.location
            repeat(20) { h.navigate() }
            h.owner.pulse(requireNotNull(h.navigationNow))
            builderNpc.location shouldBe pendingFeet
            (builder.supplyPickup != null) shouldBe true
            h.supplyRequests shouldBe 1
            h.city.workers.single { it.id == courierId }.cargo shouldBe cargo
            h.unloads shouldBe 0

            h.dropoffProvider = { cityId, workerId, delivered, target ->
                workerId shouldBe courierId
                delivered shouldBe cargo
                target shouldBe hut.id
                h.owner.requireStorageArrival(cityId, workerId, target)
                val container = h.world.getBlockAt(20, 64, 0).state as Container
                container.inventory.addItem(ItemStack(Material.GRAVEL, 7)).isEmpty() shouldBe true
                h.city = CivsLogistics.unload(h.city, workerId, cargo.id, requireNotNull(h.navigationNow))
                CompletableFuture.completedFuture(h.city)
            }
            h.releaseNavigation()
            var contactPass = false
            for (tick in 1..600) {
                val previous = courierNpc.location
                h.navigate()
                (previous.distance(courierNpc.location) <= .215001) shouldBe true
                val builderAt = builderNpc.location
                val courierAt = courierNpc.location
                builderAt shouldBe pendingFeet
                (builder.supplyPickup != null) shouldBe true
                h.yielding(h.workerId) shouldBe false
                contactPass = contactPass || h.owner.passingBodies(courierNpc)
                BoundingBox(builderAt.x - .3, builderAt.y, builderAt.z - .3,
                    builderAt.x + .3, builderAt.y + 1.8, builderAt.z + .3).overlaps(
                    BoundingBox(courierAt.x - .3, courierAt.y, courierAt.z - .3,
                        courierAt.x + .3, courierAt.y + 1.8, courierAt.z + .3)) shouldBe false
                if (tick % 20 == 0) { h.owner.pulse(requireNotNull(h.navigationNow)); h.clearRecordedCalls() }
                if (h.unloads == 1) break
            }
            withClue(h.owner.debug(h.city.id, null)) { h.unloads shouldBe 1 }
            contactPass shouldBe false // This open floor has a real path around the fixed body.
            h.supplyRequests shouldBe 1
            h.pendingSupplies.isDone shouldBe false
            h.city.workers.single { it.id == courierId }.cargo shouldBe null
            CivsEquipment.plainCount(h.city.workers.single { it.id == courierId }, "GRAVEL") shouldBe 0
            (h.world.getBlockAt(20, 64, 0).state as Container).inventory.contents.filterNotNull()
                .filter { it.type == Material.GRAVEL }.sumOf { it.amount } shouldBe 7
            h.pickups shouldBe 0 // This is the original already-carried shipment.
            h.deliveries shouldBe 0
        }
    }
})
