package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsResidentMealsTest : FunSpec({
    for (profession in listOf(BuildingType.FARM, BuildingType.BUILDER_HUT))
    test("$profession eats a carried batch on the production field-before-owner cadence without leaving work") {
        residentRuntime { h ->
            h.navigationNow = 2_000
            if (profession == BuildingType.FARM) h.autoCrop(h.assignCanonicalAutoFarm(), 0, 4_000)
            else {
                val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
                val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                    entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                    bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
                val materials = mapOf("STONE" to 1L)
                val order = CivConstruction(UUID.randomUUID(), target.id, 1, 4, 0, ConstructionPhase.PLACING,
                    CivsAutonomy.cost(target.type, target.level, materials), plannedBuilding = target,
                    batches = CivConstructionBatches(4, 16, atomicPairs = true), workCredit = 1, materials = materials)
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), hut, target), construction = order,
                    autonomy = CivAutonomy(), inventory = null))
            }
            h.owner.pulse(3_000)
            h.city = h.city.copy(stock = Stock(food = 306), workers = h.city.workers.map {
                CivsEquipment.collectSupplies(it.copy(hunger = 0, life = CivResidentLife(awakeMinutes = 20)), mapOf("BREAD" to 4L))
            })
            h.owner.residentLifeEnabled = true
            h.owner.collisionActors().single().teleport(Location(h.world, .5, 64.0, .5))
            val committedAt = mutableListOf<Long>()
            h.owner.finishResidentMeal = { cityId, plan ->
                h.owner.validateResidentMeal(cityId, plan)
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                committedAt += requireNotNull(h.navigationNow)
                CompletableFuture.completedFuture(h.city)
            }
            h.navigationNow = 4_000
            h.owner.pulse(4_000)
            for (tick in 1..100) {
                h.navigate()
                val now = requireNotNull(h.navigationNow)
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (tick % 20 == 0) h.owner.pulse(now)
                if (tick < 100) { committedAt shouldBe emptyList(); h.city.stock.food shouldBe 306L }
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                committedAt shouldBe listOf(9_000L)
                h.city.workers.single().hunger shouldBe 18
                h.city.workers.single().life!!.meals shouldBe 3L
                CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 1
                h.city.stock.food shouldBe 306L
                h.mealState()["last_outcome"] shouldBe "accepted"
            }
            h.deliveries shouldBe 0
        }
    }

    test("two hungry residents eat their own carried food without competing for hall routes") {
        residentRuntime { h ->
            val actors = h.startSocialPair()
            actors.forEachIndexed { index, npc -> npc.teleport(Location(h.world, 6.5, 64.0, if (index == 0) 4.5 else -4.5)) }
            h.prepareHallMeals(actors.mapTo(hashSetOf()) { it.uniqueId })
            val meals = mutableMapOf<UUID, Int>()
            h.owner.finishResidentMeal = { cityId, plan ->
                h.owner.validateResidentMeal(cityId, plan)
                h.city = CivsResidentLife.finishMeal(h.city, plan)
                meals[plan.workerId] = meals.getOrDefault(plan.workerId, 0) + 1
                CompletableFuture.completedFuture(h.city)
            }
            h.navigationNow = 3_000
            for (now in 3_000L..7_000L step 1_000) {
                h.navigationNow = now
                h.owner.pulse(now)
                actors.forEach { h.routeIdentity(it.uniqueId) shouldBe null }
            }
            meals shouldBe actors.associate { it.uniqueId to 1 }
            h.city.workers.all { it.hunger == 20 && it.life?.meals == 1L } shouldBe true
            h.city.stock.food shouldBe 306L
            h.deliveries shouldBe 0
        }
    }

    test("a queued meal retains its pending state but an old observation still rejects on a fast pulse") {
        residentRuntime { h ->
            h.prepareHallMeals(setOf(h.workerId))
            h.owner.collisionActors().single().teleport(Location(h.world, 1.5, 64.0, .5))
            val pending = CompletableFuture<Settlement>()
            var proof: CivResidentMeal? = null
            h.owner.finishResidentMeal = { cityId, plan ->
                h.owner.validateResidentMeal(cityId, plan)
                proof = plan
                pending
            }
            for (now in 2_000L..6_000L step 1_000) h.owner.pulse(now)
            val accepted = requireNotNull(proof)
            h.mealState()["pending"] shouldBe true
            h.owner.pulseFieldWork(9_000)
            shouldThrow<CivsRejection> { h.owner.validateResidentMeal(h.city.id, accepted) }.reason shouldBe "stale-meal"
            pending.completeExceptionally(CivsRejection("stale-meal"))
            h.owner.pulse(9_000)
            h.mealState()["pending"] shouldBe false
            h.mealState()["last_outcome"] shouldBe "stale-meal"
            h.mealState()["retry_at"] shouldBe 14_000L
            h.city.stock.food shouldBe 306L
            h.city.workers.single().hunger shouldBe 14
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
        }
    }
})
