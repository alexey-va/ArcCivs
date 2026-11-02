package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenOutputReturnTest : FunSpec({
    test("a searching miner keeps one excess item through discovery and admits its next local work") {
        residentRuntime { h ->
            val quarry = h.assignQuarry().copy(level = 2,
                schematicId = "civs/minecolonies/caledonia/fundamentals/miner2")
            val bag = List<CivResidentItem?>(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_PICKAXE", 1)
                7 -> CivResidentItem("LADDER", 14)
                8 -> CivResidentItem("TORCH", 7)
                9 -> CivResidentItem("COBBLESTONE", 33)
                10 -> CivResidentItem("DARK_OAK_LOG", 32)
                11 -> CivResidentItem("DARK_OAK_PLANKS", 32)
                12 -> CivResidentItem("DARK_OAK_FENCE", 16)
                else -> null
            } }
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == quarry.id) quarry else it },
                workers = h.city.workers.map { it.copy(items = bag, completedCycles = 6_278) })
            val before = h.city
            h.idleReason = "searching-work"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            val at = actor.location
            var returns = 0
            h.owner.returnResidentOutputs = { _, _, _ -> returns++; CompletableFuture() }
            repeat(3) { h.owner.pulse(2_000L + it * 1_000) }
            returns shouldBe 0
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"
            actor.location shouldBe at
            val focus = CivPosition(26, 61, 0)
            h.work = CivWorldWork(h.workerId, 6_278, quarry.id, CivPosition(25, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:air", "minecraft:ladder[facing=west,waterlogged=false]")),
                inputs = mapOf("LADDER" to 1L), status = "digging-mine", miningStep = 4, miningPlanVersion = 7)
            h.owner.pulse(5_000)
            val plan = ((h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>)["plan"] as Map<*, *>
            plan["focus"] shouldBe focus
            h.phase() shouldBe CitizenPhase.TO_WORK
            returns shouldBe 0
            h.city shouldBe before
            h.city.workers.single().items shouldBe bag
            h.city.workers.single().completedCycles shouldBe 6_278L
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
        }
    }

    test("searching-mine output policy retains full stacks capacity failures pauses and real idle returns") {
        residentRuntime { h ->
            val quarry = h.assignQuarry().copy(level = 2)
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == quarry.id) quarry else it })
            val runtime = CitizenRuntime(h.paper.createSimplePlugin("SearchingMineOutputPolicy")) { listOf(h.city) }
            val couriers = CivsCitizenCouriers(runtime, { error("unused routes") }, { error("unused leisure") },
                { error("unused work") }, { error("unused logistics") }, CivsPaths(), CivsResidentFoods.BREAD_ONLY,
                { _, _ -> null }, CivsNavigationSettings())
            for (scenario in listOf("searching", "output-full", "full-stack", "no-capacity", "paused", "real-idle", "other-profession")) {
                val worker = h.city.workers.single().copy(items = List(CivsEquipment.SLOTS) { slot -> when {
                    slot == 4 -> CivResidentItem("IRON_PICKAXE", 1)
                    slot == 9 -> CivResidentItem("COBBLESTONE", if (scenario == "full-stack") 64 else 33)
                    scenario == "full-stack" && slot == 13 -> CivResidentItem("COBBLESTONE", 32)
                    scenario == "no-capacity" && slot >= CivsEquipment.BAG -> CivResidentItem("IRON_PICKAXE", 1)
                    else -> null
                } })
                val city = h.city.copy(workers = listOf(worker), buildings = h.city.buildings.map { building ->
                    if (scenario == "other-profession" && building.id == quarry.id)
                        building.copy(type = BuildingType.FORESTER, production = "OAK_LOG") else building
                })
                val blockedWork = if (scenario == "no-capacity") CivWorldWork(worker.id, worker.completedCycles,
                    quarry.id, CivPosition(25, 64, 0), emptyList(), outputs = mapOf("RAW_IRON" to 1L), status = "digging-mine") else null
                val plan = CitizenPlan(CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null),
                    requireNotNull(quarry.control), requireNotNull(quarry.control),
                    if (scenario == "other-profession") BuildingType.FORESTER else BuildingType.QUARRY, 0,
                    if (scenario == "real-idle") "waiting-orders" else "searching-work", worldWork = blockedWork)
                val actor = CitizenActor(city.id, worker.id, city.worldId, mockk(relaxed = true), plan)
                if (scenario == "output-full") actor.outputFullPause = CitizenOutputFullPause(quarry.id, 9_000)
                if (scenario == "paused") actor.cycle.pause("route-unreachable", 1_000, false)
                if (scenario == "no-capacity") citizenHasCapacity(city, plan) shouldBe false
                couriers.sourceOffersCargo(actor, city, worker) shouldBe (scenario != "searching")
            }
        }
    }

    test("a waiting paid batch admits enough matching surplus without changing the ordinary stack threshold") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            val target = quarry.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, production = null,
                phase = BuildingPhase.CONSTRUCTING, anchor = CivPosition(30, 64, 0), entrance = CivPosition(30, 64, 0),
                control = null, bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16),
                materials = mapOf("COBBLESTONE" to 128L, "RAW_IRON" to 12L),
                payment = CivConstructionPayment(awaiting = mapOf("COBBLESTONE" to 12L)))
            val runtime = CitizenRuntime(h.paper.createSimplePlugin("PaidBatchOutputPolicy")) { listOf(h.city) }
            val couriers = CivsCitizenCouriers(runtime, { error("unused routes") }, { error("unused leisure") },
                { error("unused work") }, { error("unused logistics") }, CivsPaths(), CivsResidentFoods.BREAD_ONLY,
                { _, _ -> null }, CivsNavigationSettings())
            for (scenario in listOf("matching", "below-batch", "no-wait", "no-order", "foreign-material", "paused-order",
                "ordinary-full-stack", "large-batch")) {
                val count = when (scenario) { "below-batch" -> 43; "ordinary-full-stack", "large-batch" -> 96; else -> 71 }
                val worker = h.city.workers.single().copy(items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                    4 -> CivResidentItem("IRON_PICKAXE", 1)
                    6 -> CivResidentItem("COBBLESTONE", minOf(count, 64))
                    7 -> (count - 64).takeIf { it > 0 }?.let { CivResidentItem("COBBLESTONE", it) }
                    8 -> CivResidentItem("RAW_IRON", 1)
                    else -> null
                } })
                val currentOrder = when (scenario) {
                    "no-order", "ordinary-full-stack" -> null
                    "no-wait" -> order.copy(phase = ConstructionPhase.READY_TO_PLACE, payment = CivConstructionPayment())
                    "foreign-material" -> order.copy(payment = CivConstructionPayment(awaiting = mapOf("RAW_IRON" to 12L)))
                    "paused-order" -> order.copy(phase = ConstructionPhase.PAUSED, failure = "world-busy")
                    "large-batch" -> order.copy(payment = CivConstructionPayment(awaiting = mapOf("COBBLESTONE" to 128L)))
                    else -> order
                }
                val city = h.city.copy(workers = listOf(worker), construction = currentOrder,
                    buildings = h.city.buildings + if (currentOrder == null) target.copy(phase = BuildingPhase.SITE) else target)
                val plan = CitizenPlan(CitizenToken(quarry.id, worker.completedCycles, worker.nextDeliveryAt, null),
                    requireNotNull(quarry.control), requireNotNull(quarry.control), BuildingType.QUARRY, 0, "searching-work")
                val actor = CitizenActor(city.id, worker.id, city.worldId, mockk(relaxed = true), plan)
                CivsLogistics.workerExcess(city, worker) shouldBe mapOf("COBBLESTONE" to count - 32L, "RAW_IRON" to 1L)
                couriers.sourceOffersCargo(actor, city, worker) shouldBe
                    (scenario in setOf("matching", "ordinary-full-stack", "large-batch"))
                CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe count.toLong()
                CivsEquipment.plainCount(worker, "RAW_IRON") shouldBe 1L
            }
        }
    }

    test("a searching miner walks home and returns a waiting batch surplus once while retaining its work reserve") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            val target = quarry.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, production = null,
                phase = BuildingPhase.CONSTRUCTING, anchor = CivPosition(30, 64, 4), entrance = CivPosition(30, 64, 4),
                control = null, bounds = CivBox(CivPosition(28, 64, 2), CivPosition(32, 68, 6)))
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("COBBLESTONE" to 12L),
                payment = CivConstructionPayment(awaiting = mapOf("COBBLESTONE" to 12L)))
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + target, construction = order,
                workers = h.city.workers.map { it.copy(completedCycles = 1_238, items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                    4 -> CivResidentItem("IRON_PICKAXE", 1)
                    6 -> CivResidentItem("COBBLESTONE", 64)
                    7 -> CivResidentItem("COBBLESTONE", 7)
                    8 -> CivResidentItem("LADDER", 16)
                    9 -> CivResidentItem("TORCH", 8)
                    else -> null
                } }) }))
            h.idleReason = "searching-work"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 28.5, 64.0, .5))
            val start = actor.location.clone()
            val control = requireNotNull(quarry.control)
            val barrel = (h.world.getBlockAt(control.x, control.y, control.z).state as Barrel).inventory
            barrel.setItem(0, ItemStack(Material.COBBLESTONE, 32))
            val pending = CompletableFuture<Settlement>()
            var requests = 0
            h.owner.returnResidentOutputs = { cityId, workerId, buildingId ->
                buildingId shouldBe quarry.id
                h.owner.requireStorageArrival(cityId, workerId, buildingId)
                requests++
                pending
            }
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            requests shouldBe 0
            for (tick in 1..200) {
                h.navigate()
                if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (requests > 0) break
            }
            requests shouldBe 1
            (actor.location.distance(start) > 4.0) shouldBe true
            CivsEquipment.plainCount(h.city.workers.single(), "COBBLESTONE") shouldBe 71L
            barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
            CivsStorage(h.paper.createSimplePlugin("PaidBatchOutputReturn")) { emptyArray() }.use { storage ->
                val before = h.city
                val worker = before.workers.single()
                val id = UUID.randomUUID()
                storage.reserve(before, id)
                val returned = storage.prepareResidentReturn(before, worker.id, quarry.id)
                CivsEquipment.plainCount(returned.worker, "COBBLESTONE") shouldBe 32L
                CivsEquipment.plainCount(returned.worker, "LADDER") shouldBe 16L
                CivsEquipment.plainCount(returned.worker, "TORCH") shouldBe 8L
                returned.worker.items!![4] shouldBe worker.items!![4]
                returned.worker.completedCycles shouldBe worker.completedCycles
                val after = before.copy(workers = listOf(returned.worker), inventory = returned.plan.inventoryAfter,
                    revision = before.revision + 1)
                val receipt = CivInventoryTransaction(id, before.id, before.revision, after, returned.plan.changes).validated()
                storage.seal(receipt)
                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                barrel.contents.filterNotNull().filter { it.type == Material.COBBLESTONE }.sumOf { it.amount } shouldBe 71
                h.city = after
                pending.complete(after)
                h.owner.pulse(requireNotNull(h.navigationNow) + 1_000)
                h.owner.pulse(requireNotNull(h.navigationNow) + 2_000)
                requests shouldBe 1
                h.city.construction shouldBe order
                h.city.workers.single().completedCycles shouldBe 1_238L
                h.deliveries shouldBe 0
                h.pickups shouldBe 0
                h.unloads shouldBe 0
                storage.unlock(id)
            }
        }
    }

    for (reason in listOf("storage-full", "inventory-recovery", "storage-unavailable", "wrapped-storage-full"))
    test("an idle miner retains its bag and transfer identity after output return $reason") {
        residentRuntime { h ->
            val quarry = h.assignQuarry().copy(level = 2,
                schematicId = "civs/minecolonies/caledonia/fundamentals/miner2")
            // A genuinely idle producer may unload one excess item; accepted transfer errors retain their own pin.
            val bag = List<CivResidentItem?>(CivsEquipment.SLOTS) { slot -> when (slot) {
                4 -> CivResidentItem("IRON_PICKAXE", 1)
                7 -> CivResidentItem("LADDER", 14)
                8 -> CivResidentItem("TORCH", 7)
                9 -> CivResidentItem("COBBLESTONE", 33)
                10 -> CivResidentItem("DARK_OAK_LOG", 32)
                11 -> CivResidentItem("DARK_OAK_PLANKS", 32)
                12 -> CivResidentItem("DARK_OAK_FENCE", 16)
                else -> null
            } }
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == quarry.id) quarry else it },
                workers = h.city.workers.map { it.copy(items = bag, completedCycles = 6_278) })
            val before = h.city
            CivsLogistics.workerExcess(h.city, h.city.workers.single()) shouldBe mapOf("COBBLESTONE" to 1L)
            h.idleReason = "waiting-orders"
            h.owner.collisionActors().single().teleport(Location(h.world, 21.5, 64.0, .5))
            val returned = CompletableFuture<Settlement>()
            var returns = 0
            h.owner.returnResidentOutputs = { cityId, workerId, buildingId ->
                buildingId shouldBe quarry.id
                h.owner.requireStorageArrival(cityId, workerId, buildingId)
                returns++
                returned
            }
            fun currentPlan(): Map<*, *> =
                ((h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>)["plan"] as Map<*, *>

            h.owner.pulse(2_000)
            returns shouldBe 1
            h.phase() shouldBe CitizenPhase.IDLE
            currentPlan()["idle_reason"] shouldBe "waiting-orders"
            currentPlan()["focus"] shouldBe null

            val focus = CivPosition(26, 61, 0)
            h.work = CivWorldWork(h.workerId, 6_278, quarry.id, CivPosition(25, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:air", "minecraft:ladder[facing=west,waterlogged=false]")),
                inputs = mapOf("LADDER" to 1L), status = "digging-mine", miningStep = 4, miningPlanVersion = 7)
            // Pending native transfers retain the accepted plan even though discovery has caught up.
            h.owner.pulse(3_000)
            returns shouldBe 1
            currentPlan()["focus"] shouldBe null
            h.city shouldBe before

            returned.completeExceptionally(if (reason == "wrapped-storage-full")
                IllegalStateException("Transfer outcome is uncertain", CivsRejection("storage-full"))
                else CivsRejection(reason))
            h.owner.pulse(4_000)
            val outputPause = ((h.owner.debug(h.city.id, h.workerId)["entries"] as List<*>).single() as Map<*, *>)["output_full"]
            (outputPause != null) shouldBe (reason == "storage-full")
            if (reason == "storage-full") {
                currentPlan()["focus"] shouldBe null
                currentPlan()["idle_reason"] shouldBe "waiting-orders"
                h.phase() shouldBe CitizenPhase.IDLE
                h.owner.status(h.workerId) shouldBe "workers.status.storage-full"
                citizenHasCapacity(h.city, citizenPlan(h.city, h.city.workers.single())!!.copy(worldWork = h.work)) shouldBe true
            } else {
                currentPlan()["focus"] shouldBe null
                currentPlan()["idle_reason"] shouldBe "waiting-orders"
                h.phase() shouldBe CitizenPhase.IDLE
            }
            returns shouldBe 1
            h.city shouldBe before
            h.city.workers.single().items shouldBe bag
            h.city.workers.single().completedCycles shouldBe 6_278L
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
        }
    }
})
