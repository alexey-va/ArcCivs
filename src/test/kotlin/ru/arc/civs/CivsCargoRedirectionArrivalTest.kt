package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCargoRedirectionArrivalTest : FunSpec({
    test("a redirected courier walks to its new barrel and its receipt rejects a later remote location") {
        residentRuntime { h ->
            val (source, courierId) = h.courierSource(material = "TORCH", amount = 1)
            val original = h.city.buildings.single { it.type == BuildingType.WAREHOUSE }
            val point = CivPosition(10, 64, 10)
            val alternative = original.copy(id = UUID.randomUUID(), anchor = point, entrance = point,
                control = point, bounds = CivBox(CivPosition(8, 64, 8), CivPosition(12, 68, 12)))
            for (x in -1..2) for (z in -1..2) h.world.loadChunk(x, z)
            h.world.getBlockAt(point.x, point.y, point.z).type = Material.BARREL
            val cargo = CivCargo(CivsLogistics.shipmentId(h.city.id, courierId, 0, source.id, original.id, "TORCH"),
                original.id, source.id, original.id, source.entrance, "TORCH", 1, inventoryBacked = true)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + alternative,
                workers = h.city.workers.filter { it.id == courierId }.map {
                    CivsEquipment.collectSupplies(it, mapOf("TORCH" to 1L))
                }, inventory = CivInventory(CivStorageMode.PHYSICAL)))
            val accepted = cargo.copy(deliveryBuildingId = alternative.id)
            h.courierProvider = { city, worker -> worker.cargo?.let { carried ->
                CivsLogistics.destination(city, carried)?.let { target -> CivCourierPlan(carried, target.id, target.entrance, true) }
            } }
            var full = true
            h.dropoffProvider = { cityId, workerId, carried, destination ->
                carried shouldBe accepted
                destination shouldBe alternative.id
                h.owner.requireStorageArrival(cityId, workerId, destination)
                if (full) {
                    // Capacity can change before the already-completed rejection reaches the actor drain.
                    h.phase(workerId) shouldBe CitizenPhase.DELIVERING
                    h.owner.retryCargoDelivery(cityId, workerId, carried.id) shouldBe false
                    CompletableFuture.failedFuture(CivsRejection("storage-full", mapOf("retry_millis" to "30000")))
                } else h.pendingUnload
            }
            h.idleReason = "searching-work"
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            courier.teleport(Location(h.world, 11.5, 64.0, .5)) // Initial placement before offering the carried shipment.
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(cargo = cargo) })
            h.city = CivsLogistics.redirect(h.city, courierId, cargo, alternative.id)
            h.navigationNow = 3_000
            h.owner.pulse(3_000)
            h.unloads shouldBe 0
            shouldThrow<CivsRejection> { h.owner.requireStorageArrival(h.city.id, courierId, alternative.id) }
                .reason shouldBe "route-unreachable"
            for (tick in 1..1_200) {
                h.navigate()
                if (tick % 5 == 0) h.owner.pulseFieldWork(requireNotNull(h.navigationNow))
                if (h.phase(courierId) == CitizenPhase.PAUSED && h.unloads == 1) break
            }
            withClue(h.owner.debug(h.city.id, courierId)) {
                h.unloads shouldBe 1
                h.phase(courierId) shouldBe CitizenPhase.PAUSED
            }
            val feet = courier.location
            citizenAtStorage(h.world, feet.point(), point) shouldBe true
            // A capacity notification may wake only its own shipment, even during its longer cooldown.
            h.owner.retryCargoDelivery(h.city.id, courierId, UUID.randomUUID())
            h.phase(courierId) shouldBe CitizenPhase.PAUSED
            full = false
            h.owner.retryCargoDelivery(h.city.id, courierId, accepted.id) shouldBe true
            h.phase(courierId) shouldBe CitizenPhase.READY
            h.owner.pulseFieldWork(requireNotNull(h.navigationNow) + 250)
            h.unloads shouldBe 2
            h.phase(courierId) shouldBe CitizenPhase.DELIVERING

            CivsStorage(h.paper.createSimplePlugin("RedirectedCargoReceipt")) { emptyArray() }.use { storage ->
                storage.foodCanModify = { _, _ -> true }
                val before = h.city
                val id = UUID.randomUUID()
                storage.reserve(before, id)
                courier.teleport(Location(h.world, 11.5, 64.0, .5)) // Negative case: moved away after the earlier arrival.
                shouldThrow<CivsRejection> { storage.prepareCarriedCargoDropoff(before, courierId, accepted, alternative.id, 50_000) {
                    h.owner.requireStorageArrival(before.id, courierId, alternative.id)
                } }.reason shouldBe "route-unreachable"
                val bin = (h.world.getBlockAt(point.x, point.y, point.z).state as Barrel).inventory
                bin.isEmpty shouldBe true
                CivsEquipment.plainCount(before.workers.single(), "TORCH") shouldBe 1
                courier.teleport(feet)
                val plan = storage.prepareCarriedCargoDropoff(before, courierId, accepted, alternative.id, 50_000) {
                    h.owner.requireStorageArrival(before.id, courierId, alternative.id)
                }
                val after = CivsLogistics.unload(before, courierId, accepted.id, 50_000).copy(inventory = plan.inventoryAfter)
                val tx = CivInventoryTransaction(id, before.id, before.revision, after, plan.changes, cargoDropoff = plan.cargoDropoff)
                storage.seal(tx)
                storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                bin.getItem(0) shouldBe ItemStack(Material.TORCH, 1)
                h.city = after
                h.pendingUnload.complete(after)
                h.owner.pulseFieldWork(requireNotNull(h.navigationNow) + 500)
                h.city.workers.single().cargo shouldBe null
                CivsEquipment.plainCount(h.city.workers.single(), "TORCH") shouldBe 0
                h.city.workers.single().completedCycles shouldBe 1L
                storage.unlock(id)
            }
        }
    }
})
