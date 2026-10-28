package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.concurrent.CompletableFuture

class CivsTavernStaffingRuntimeTest : FunSpec({
    for (profession in listOf(BuildingType.FARM, BuildingType.GUARD_TOWER))
    test("a $profession in working phase retains its accepted work") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            if (profession == BuildingType.FARM) h.work = CivWorldWork(h.workerId, 0, farm.id,
                CivPosition(19, 64, 0), emptyList(), durationMillis = 10_000, status = "working-field")
            else h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.id == farm.id) it.copy(type = profession, production = null) else it
            }))
            val npc = h.owner.collisionActors().single()
            for (now in 2_000L..6_000L step 1_000) {
                h.owner.pulse(now)
                if (h.phase() == CitizenPhase.WORKING) break
                h.routeTarget()?.let(npc::teleport)
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) { h.phase() shouldBe CitizenPhase.WORKING }
            h.owner.canReassign(h.city.id, h.workerId) shouldBe false
            h.deliveries shouldBe 0
        }
    }

    test("only an idle standing tavernkeeper with no personal meal can be reassigned from working") {
        residentRuntime { h ->
            try {
                val keeper = h.standingTavernkeeper()
                h.phase(keeper) shouldBe CitizenPhase.WORKING
                h.owner.canReassign(h.city.id, keeper) shouldBe true
                h.city = h.city.copy(workers = h.city.workers.map {
                    if (it.id == keeper) it.copy(hunger = 14, inventory = mapOf("BREAD" to 1L)) else it.copy(hunger = 20)
                })
                h.owner.residentLifeEnabled = true
                h.owner.pulse(5_000)
                h.mealState(keeper)["building"] shouldBe h.city.buildings.first { it.type == BuildingType.TAVERN }.id
                h.mealState(keeper)["source"] shouldBe CivResidentMealSource.CARRIED_BREAD
                h.owner.canReassign(h.city.id, keeper) shouldBe false
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    for (pendingReceipt in listOf(false, true))
    test("accepted building borrows an idle keeper while its self-service guest continues pending receipt=$pendingReceipt") {
        residentRuntime { h ->
            try {
                val keeper = h.standingTavernkeeper()
                for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                val guest = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                guest.teleport(Location(h.world, .5, 64.0, .5))
                val pending = CompletableFuture<Settlement>()
                var proof: CivResidentMeal? = null
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    proof = plan
                    meals++
                    pending
                }
                h.owner.residentLifeEnabled = true
                h.owner.pulse(5_000)
                meals shouldBe 0
                guest.teleport(requireNotNull(h.routeTarget()))
                val mealAt = guest.location
                h.owner.pulse(6_000)
                if (pendingReceipt) for (now in 7_000L..10_000L step 1_000) {
                    h.owner.pulse(now)
                    meals shouldBe if (now < 10_000) 0 else 1
                }
                h.mealState()["building"] shouldBe tavern.id
                h.mealState()["pending"] shouldBe pendingReceipt
                h.mealState()["arrived_at"] shouldBe 6_000L
                h.mealState()["source"] shouldBe CivResidentMealSource.CARRIED_BREAD
                guest.location shouldBe mealAt
                h.usingMainHand shouldBe true
                h.displayedHand shouldBe Material.BREAD
                CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "BREAD") shouldBe 2
                val hut = h.acceptTavernConstruction()
                h.owner.canReassign(h.city.id, keeper) shouldBe true
                CivsAutonomy.criticalAssignment(h.city) { it == keeper && h.owner.canReassign(h.city.id, it) } shouldBe (keeper to hut.id)
                val assigned = CivsRules.autoAssign(h.city, h.city.owner, 11_000) {
                    it == keeper && h.owner.canReassign(h.city.id, it)
                }
                assigned.workers.first { it.id == keeper }.assignment shouldBe hut.id
                assigned.constructionQueue shouldBe h.city.constructionQueue
                assigned.stock shouldBe h.city.stock
                h.city = assigned
                if (!pendingReceipt) for (later in 7_000L..10_000L step 1_000) {
                    h.owner.pulse(later)
                    meals shouldBe if (later < 10_000) 0 else 1
                }
                h.city = CivsResidentLife.finishMeal(h.city, requireNotNull(proof))
                pending.complete(h.city) shouldBe true
                h.owner.pulse(11_000)
                h.city.stock.food shouldBe 2L
                CivsEquipment.plainCount(h.city.workers.first { it.id == h.workerId }, "BREAD") shouldBe 1
                h.usingMainHand shouldBe false
                h.displayedHand shouldBe Material.IRON_AXE
                meals shouldBe 1
                h.city.workers.first { it.id == h.workerId }.life!!.meals shouldBe 1L
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }
})
