package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.concurrent.CompletableFuture

class CivsCitizenOutputReturnTest : FunSpec({
    for (reason in listOf("storage-full", "inventory-recovery", "storage-unavailable", "wrapped-storage-full"))
    test("an idle miner reconsiders cached ladder work only after a definite output return $reason") {
        residentRuntime { h ->
            val quarry = h.assignQuarry().copy(level = 2,
                schematicId = "civs/minecolonies/caledonia/fundamentals/miner2")
            // Regina's observed carried batch: one cobblestone above the useful work reserve.
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
            h.idleReason = "searching-work"
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
            currentPlan()["idle_reason"] shouldBe "searching-work"
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
            if (reason == "storage-full") {
                currentPlan()["focus"] shouldBe focus
                currentPlan()["standing"] shouldBe h.work!!.standing
                currentPlan()["mining_step"] shouldBe 4L
                currentPlan()["idle_reason"] shouldBe null
                h.phase() shouldBe CitizenPhase.TO_WORK
                citizenHasCapacity(h.city, citizenPlan(h.city, h.city.workers.single())!!.copy(worldWork = h.work)) shouldBe true
            } else {
                currentPlan()["focus"] shouldBe null
                currentPlan()["idle_reason"] shouldBe "searching-work"
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
