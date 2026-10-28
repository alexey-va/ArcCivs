package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsResidentMealsTest : FunSpec({
    test("new meals use the hall while the sole tavernkeeper awaits accepted building work") {
        residentRuntime { h ->
            try {
                h.standingTavernkeeper()
                h.acceptTavernConstruction()
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                val hall = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }
                CivsRules.staffedTavern(h.city, tavern) shouldBe true
                h.owner.residentLifeEnabled = true
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    plan.buildingId shouldBe hall.id
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.pulse(5_000)
                h.mealState()["building"] shouldBe hall.id
                val target = h.mealState()["target"] as CivPosition
                h.owner.collisionActors().first { it.uniqueId == h.workerId }
                    .teleport(Location(h.world, target.x + .5, target.y.toDouble(), target.z + .5))
                for (now in 6_000L..11_000L step 1_000) h.owner.pulse(now)
                meals shouldBe 1
                h.city.stock.food shouldBe 1L
                h.city.workers.first { it.id == h.workerId }.life!!.meals shouldBe 1L
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    for (profession in listOf(BuildingType.FARM, BuildingType.BUILDER_HUT))
    test("$profession completes a meal on the production field-before-owner five and twenty tick cadence") {
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
                it.copy(hunger = 0, life = CivResidentLife(awakeMinutes = 20))
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
            for (tick in 1..80) {
                h.navigate()
                val now = requireNotNull(h.navigationNow)
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (tick % 20 == 0) h.owner.pulse(now)
                if (tick < 80) { committedAt shouldBe emptyList(); h.city.stock.food shouldBe 306L }
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                committedAt shouldBe listOf(8_000L)
                h.city.workers.single().hunger shouldBe 6
                h.city.workers.single().life!!.meals shouldBe 1L
                h.city.stock.food shouldBe 305L
                h.mealState()["last_outcome"] shouldBe "accepted"
            }
            h.deliveries shouldBe 0
        }
    }

    test("two hungry residents reserve different hall sides and both eat after their own arrival") {
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
            h.owner.pulse(3_000)
            actors.map { requireNotNull(h.routeTarget(it.uniqueId)).blockX }.toSet() shouldBe setOf(-1, 1)
            val routes = actors.associate { it.uniqueId to h.routeIdentity(it.uniqueId) }
            h.owner.pulseFieldWork(3_250)
            actors.forEach { (h.routeIdentity(it.uniqueId) === routes[it.uniqueId]) shouldBe true }
            for (tick in 1..1_200) {
                h.navigate()
                val now = requireNotNull(h.navigationNow)
                if (tick % 5 == 0) h.owner.pulseFieldWork(now)
                if (tick % 20 == 0) { h.owner.pulse(now); h.clearRecordedCalls() }
                if (meals.size == 2) break
            }
            withClue(h.owner.debug(h.city.id, null)) { meals shouldBe actors.associate { it.uniqueId to 1 } }
            h.city.workers.all { it.hunger == 20 && it.life?.meals == 1L } shouldBe true
            h.city.stock.food shouldBe 304L
            h.deliveries shouldBe 0
        }
    }

    for (occupiedAfterAdmission in listOf(false, true))
    test("hall meal chooses another side when the first is occupied after admission=$occupiedAfterAdmission") {
        residentRuntime { h ->
            val actors = h.startSocialPair()
            val eater = actors.first { it.uniqueId == h.workerId }
            val neighbour = actors.first { it !== eater }
            eater.teleport(Location(h.world, 6.5, 64.0, 4.5))
            neighbour.teleport(Location(h.world, if (occupiedAfterAdmission) 8.5 else 1.5, 64.0, .5))
            h.prepareHallMeals(setOf(h.workerId))
            h.owner.pulse(3_000)
            if (occupiedAfterAdmission) {
                h.routeTarget()!!.blockX shouldBe 1
                val route = h.routeIdentity()
                neighbour.teleport(Location(h.world, 1.5, 64.0, .5))
                h.owner.pulse(4_000)
                (h.routeIdentity() === route) shouldBe false
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                h.routeTarget()!!.blockX shouldBe -1
                h.mealState()["arrived_at"] shouldBe null
                h.mealState()["pending"] shouldBe false
            }
            h.city.workers.first { it.id == h.workerId }.hunger shouldBe 14
            h.city.stock.food shouldBe 306L
        }
    }

    test("an occupied hall keeps food unchanged and retries only after its local cooldown") {
        residentRuntime { h ->
            val blockers = (1..4).map { CivWorker(UUID(0, it.toLong()), "Neighbour$it", null) }
            h.city = h.city.copy(workers = h.city.workers + blockers)
            h.owner.pulse(2_000)
            h.owner.pulse(2_500)
            val spots = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
            val actors = h.owner.collisionActors()
            actors.single { it.uniqueId == h.workerId }.teleport(Location(h.world, 6.5, 64.0, 4.5))
            blockers.zip(spots).forEach { (worker, spot) ->
                actors.single { it.uniqueId == worker.id }.teleport(Location(h.world, spot.first + .5, 64.0, spot.second + .5))
            }
            h.prepareHallMeals(setOf(h.workerId))
            h.owner.pulse(3_000)
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                h.mealState()["target"] shouldBe null
                h.mealState()["retry_at"] shouldBe 8_000L
                h.mealState()["last_outcome"] shouldBe "no-free-target"
                (h.owner.status(h.workerId) == "workers.status.to-meal") shouldBe false
            }
            h.city = h.city.copy(workers = h.city.workers.filter { it.id == h.workerId })
            h.owner.pulse(7_999)
            h.mealState()["target"] shouldBe null
            h.owner.pulse(8_000)
            h.mealState()["target"] shouldBe CivPosition(1, 64, 0)
            h.city.stock.food shouldBe 306L
            h.city.workers.single().hunger shouldBe 14
        }
    }

    test("refused meal route releases only that visit and exposes an explicit retry") {
        residentRuntime { h ->
            // Both endpoints are loaded, but the route's required eastern margin is not.
            h.owner.collisionActors().single().teleport(Location(h.world, 31.5, 64.0, .5))
            h.prepareHallMeals(setOf(h.workerId))
            h.owner.pulse(2_000)
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                h.mealState()["target"] shouldBe null
                h.mealState()["retry_at"] shouldBe 7_000L
                h.mealState()["last_outcome"] shouldBe "route-refused"
                (h.owner.status(h.workerId) == "workers.status.to-meal") shouldBe false
                h.routeIdentity() shouldBe null
            }
            h.city.stock.food shouldBe 306L
            h.city.workers.single().hunger shouldBe 14
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
        }
    }
})
