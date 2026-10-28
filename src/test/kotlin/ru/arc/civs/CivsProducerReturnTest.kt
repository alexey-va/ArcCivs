package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsProducerReturnTest : FunSpec({
    test("a partial workplace chest keeps overflow equipment and food in the bag and replays once") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("producer-return")
            world.loadChunk(0, 0)
            val plugin = paper.createSimplePlugin("ProducerReturn")
            val control = CivPosition(0, 64, 0)
            val local = CivPosition(1, 64, 0)
            val outside = CivPosition(6, 64, 0)
            world.getBlockAt(0, 64, 0).type = Material.BARREL
            world.getBlockAt(1, 64, 0).type = Material.CHEST
            world.getBlockAt(6, 64, 0).type = Material.CHEST
            val barrel = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
            val chest = (world.getBlockAt(1, 64, 0).state as Chest).blockInventory
            val foreign = (world.getBlockAt(6, 64, 0).state as Chest).blockInventory
            repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
            repeat(chest.size) { chest.setItem(it, ItemStack(Material.STONE, 64)) }
            barrel.setItem(0, ItemStack(Material.OAK_LOG, 60))
            val building = CivBuilding(UUID.randomUUID(), BuildingType.FORESTER, 1, control, 0,
                "forester1", CivBox(control, CivPosition(2, 68, 2)), CivPosition(0, 64, -1), BuildingPhase.ACTIVE,
                control = control)
            val worker = CivWorker(UUID.randomUUID(), "Producer", building.id, completedCycles = 7, nextDeliveryAt = 900,
                items = List(CivsEquipment.SLOTS) { when (it) {
                    4 -> CivResidentItem("IRON_AXE", 1)
                    6 -> CivResidentItem("OAK_LOG", 64)
                    7 -> CivResidentItem("OAK_SAPLING", 4)
                    8 -> CivResidentItem("BREAD", 16)
                    9 -> CivResidentItem("COAL", 6)
                    else -> null
                } })
            val hall = building.copy(id = UUID.randomUUID(), type = BuildingType.TOWN_HALL,
                anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, -1), control = CivPosition(10, 64, 0),
                bounds = CivBox(CivPosition(8, 64, -2), CivPosition(12, 68, 2)))
            val city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Return", world.uid,
                control, 32, "nordic", Stock(), listOf(hall, building), listOf(worker),
                inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(local, outside))))
            CivsStorage(plugin) { arrayOf(world.getBlockAt(1, 64, 0).state, world.getBlockAt(6, 64, 0).state) }.use { storage ->
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val prepared = storage.prepareResidentReturn(city, worker.id, building.id)
                barrel.getItem(0)!!.amount shouldBe 60
                foreign.isEmpty shouldBe true
                CivsEquipment.plainCount(prepared.worker, "OAK_LOG") shouldBe 60
                CivsEquipment.plainCount(prepared.worker, "COAL") shouldBe 6
                prepared.worker.items!!.take(6) shouldBe worker.items!!.take(6)
                CivsEquipment.plainCount(prepared.worker, "OAK_SAPLING") shouldBe 4
                CivsEquipment.plainCount(prepared.worker, "BREAD") shouldBe 16
                prepared.worker.completedCycles shouldBe 7
                prepared.worker.nextDeliveryAt shouldBe 900
                prepared.plan.changes.map { it.container }.distinct() shouldBe listOf(control)
                val after = city.copy(workers = listOf(prepared.worker), inventory = prepared.plan.inventoryAfter, revision = city.revision + 1)
                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, prepared.plan.changes).validated()
                storage.seal(receipt)
                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                barrel.getItem(0)!!.amount shouldBe 64
                storage.unlock(id)
                // A new storage owner after restart recognizes native after-state instead of crediting it twice.
                CivsStorage(plugin) { emptyArray() }.use { restarted ->
                    restarted.lock(receipt)
                    restarted.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                    barrel.getItem(0)!!.amount shouldBe 64
                    foreign.isEmpty shouldBe true
                }
            }
        }
    }

    test("a producer walks to its workplace before returning a full stack and retains its work identity") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            h.idleReason = "waiting-growth"
            h.city = h.city.copy(workers = h.city.workers.map {
                it.copy(items = List(CivsEquipment.SLOTS) { slot -> if (slot == CivsEquipment.BAG) CivResidentItem("WHEAT", 64) else null })
            })
            val npc = h.owner.collisionActors().single()
            npc.teleport(org.bukkit.Location(h.world, 28.5, 64.0, .5))
            var requests = 0
            val pending = CompletableFuture<Settlement>()
            h.owner.returnResidentOutputs = { cityId, workerId, buildingId ->
                cityId shouldBe h.city.id
                workerId shouldBe h.workerId
                buildingId shouldBe farm.id
                h.owner.requireStorageArrival(cityId, workerId, buildingId)
                requests++
                pending
            }
            h.owner.pulse(2_000)
            requests shouldBe 0
            h.owner.status(h.workerId) shouldBe "workers.status.moving-to-storage"
            npc.teleport(org.bukkit.Location(h.world, 21.5, 64.0, .5))
            h.owner.pulse(3_000)
            requests shouldBe 1
            h.owner.pulse(4_000)
            requests shouldBe 1
            CivsEquipment.plainCount(h.city.workers.single(), "WHEAT") shouldBe 64
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) }, revision = h.city.revision + 1)
            pending.complete(h.city)
            h.owner.pulse(5_000)
            h.city.workers.single().completedCycles shouldBe 0
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            requests shouldBe 1
        }
    }

    test("a courier at the building picks physical stock while its producer is far away") {
        residentRuntime { h ->
            val (farm, courierId) = h.courierSource(material = "WHEAT", amount = 1)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(items = null) })
            h.idleReason = "waiting-growth"
            val producer = h.owner.collisionActors().single()
            producer.teleport(org.bukkit.Location(h.world, 30.5, 64.0, .5))
            val accepted = requireNotNull(CivsLogistics.plan(h.city, h.city.workers.last()) {
                if (it == farm.id) mapOf("WHEAT" to 32L) else emptyMap()
            })
            h.courierProvider = { _, worker -> accepted.takeIf { worker.id == courierId } }
            h.onPickup = { cityId, workerId, cargo ->
                cargo.sourceWorkerId shouldBe null
                h.owner.requireStorageArrival(cityId, workerId, farm.id)
            }
            h.owner.pulse(2_000)
            val courier = h.owner.collisionActors().single { it.uniqueId == courierId }
            h.pickups shouldBe 0
            courier.teleport(org.bukkit.Location(h.world, 21.5, 64.0, .5))
            for (now in listOf(3_000L, 4_000L, 5_000L, 6_000L)) h.owner.pulse(now)
            h.pickups shouldBe 1
            h.pickedCargo shouldBe accepted.cargo
            producer.location.x shouldBe 30.5
        }
    }
})
