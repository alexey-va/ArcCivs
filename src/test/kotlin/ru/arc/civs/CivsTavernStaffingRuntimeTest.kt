package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
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
                h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = if (it.id == keeper) 14 else 20) })
                h.owner.residentLifeEnabled = true
                h.owner.pulse(5_000)
                h.mealState(keeper)["building"] shouldBe h.city.buildings.first { it.type == BuildingType.TAVERN }.id
                h.owner.canReassign(h.city.id, keeper) shouldBe false
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    for (pendingReceipt in listOf(false, true))
    test("accepted building waits for an existing tavern guest including pending receipt=$pendingReceipt") {
        residentRuntime { h ->
            try {
                val keeper = h.standingTavernkeeper()
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                val guest = h.owner.collisionActors().first { it.uniqueId == h.workerId }
                if (!pendingReceipt) guest.teleport(Location(h.world, 10.5, 64.0, .5))
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
                if (pendingReceipt) for (now in 6_000L..9_000L step 1_000) h.owner.pulse(now)
                h.mealState()["building"] shouldBe tavern.id
                h.mealState()["pending"] shouldBe pendingReceipt
                h.mealState()["arrived_at"] shouldBe if (pendingReceipt) 5_000L else null
                val hut = h.acceptTavernConstruction()
                h.owner.canReassign(h.city.id, keeper) shouldBe false
                CivsAutonomy.criticalAssignment(h.city) { it == keeper && h.owner.canReassign(h.city.id, it) } shouldBe null
                val now = if (pendingReceipt) 10_000L else 6_000L
                h.owner.pulse(now)
                h.mealState()["building"] shouldBe tavern.id
                h.city.stock.food shouldBe 2L
                h.owner.canReassign(h.city.id, keeper) shouldBe false
                if (pendingReceipt) {
                    h.city = CivsResidentLife.finishMeal(h.city, requireNotNull(proof))
                    pending.complete(h.city) shouldBe true
                    h.owner.pulse(11_000)
                    h.owner.canReassign(h.city.id, keeper) shouldBe true
                    CivsAutonomy.criticalAssignment(h.city) { it == keeper && h.owner.canReassign(h.city.id, it) } shouldBe (keeper to hut.id)
                    val assigned = CivsRules.autoAssign(h.city, h.city.owner, 11_000) {
                        it == keeper && h.owner.canReassign(h.city.id, it)
                    }
                    assigned.workers.first { it.id == keeper }.assignment shouldBe hut.id
                    assigned.constructionQueue shouldBe h.city.constructionQueue
                    assigned.stock shouldBe h.city.stock
                    h.city.stock.food shouldBe 1L
                    meals shouldBe 1
                    h.city.workers.first { it.id == h.workerId }.life!!.meals shouldBe 1L
                }
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }
})
