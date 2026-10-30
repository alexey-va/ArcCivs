package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Location
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
