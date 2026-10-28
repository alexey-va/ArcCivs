package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.concurrent.CompletableFuture

class CivsTavernMealsRuntimeTest : FunSpec({
    test("a worker at its ordinary work route endpoint walks to the tavern and commits its meal there") {
        residentRuntime { h ->
            try {
                val npc = h.prepareDiningSeat()
                h.owner.residentLifeEnabled = false
                for (x in -2..2) for (z in -2..1) h.world.loadChunk(x, z)
                h.city = h.city.copy(autonomy = null, stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL),
                    workers = h.city.workers.filter { it.id == h.workerId }.map {
                        it.copy(items = CivsEquipment.slots(it), inventory = emptyMap())
                    })
                val farm = h.assignFarm()
                h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(22, 64, 0), emptyList(),
                    durationMillis = 60_000, status = "working-field")
                npc.teleport(Location(h.world, .5, 64.0, .5))
                h.owner.pulse(3_000)
                h.phase() shouldBe CitizenPhase.TO_WORK
                // Start the meal at the existing work endpoint, before the coarse pulse accepts arrival.
                npc.teleport(requireNotNull(h.routeTarget()))
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                civsCanReachContainer(npc.location, requireNotNull(tavern.control)) shouldBe false
                h.steps.clear()
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    plan.buildingId shouldBe tavern.id
                    civsCanReachContainer(npc.location, requireNotNull(tavern.control)) shouldBe true
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.navigationNow = 4_000
                h.owner.residentLifeEnabled = true
                h.owner.pulse(4_000)
                h.phase() shouldBe CitizenPhase.TO_WORK
                h.owner.status(h.workerId) shouldBe "workers.status.moving-to-tavern"
                for (tick in 1..1_200) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (meals == 1) break
                }
                withClue(h.owner.debug(h.city.id, h.workerId)) { meals shouldBe 1 }
                h.steps.isNotEmpty() shouldBe true
                CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1
                h.city.workers.single().life!!.meals shouldBe 1L
                h.city.stock shouldBe Stock()
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("native packet navigation reaches the tavern and completes a meal without teleporting to it") {
        residentRuntime { h ->
            try {
                val npc = h.prepareDiningSeat()
                for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
                h.city = h.city.copy(workers = h.city.workers.filter { it.id == h.workerId }, stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL))
                npc.teleport(Location(h.world, .5, 64.0, .5))
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    plan.buildingId shouldBe tavern.id
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.navigationNow = 3_000
                h.owner.pulse(3_000)
                for (tick in 1..1_200) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (meals == 1) break
                }
                withClue(h.owner.debug(h.city.id, h.workerId)) { meals shouldBe 1 }
                (h.steps.isNotEmpty()) shouldBe true
                civsCanReachContainer(npc.location, requireNotNull(tavern.control)) shouldBe true
                CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1
                h.city.stock shouldBe Stock()
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("a resident carrying bread walks to an unstaffed native tavern before its meal session") {
        residentRuntime { h ->
            try {
                val npc = h.prepareDiningSeat()
                for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
                h.city = h.city.copy(workers = h.city.workers.filter { it.id == h.workerId }, stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL))
                npc.teleport(Location(h.world, .5, 64.0, .5))
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    plan.buildingId shouldBe tavern.id
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.pulse(3_000)
                withClue(h.owner.debug(h.city.id, h.workerId)) { h.owner.status(h.workerId) shouldBe "workers.status.moving-to-tavern" }
                val target = requireNotNull(h.routeTarget()) { h.owner.debug(h.city.id, h.workerId).toString() }
                (target.distanceSquared(npc.location) > 4) shouldBe true
                for (now in 4_000L..8_000L step 1_000) h.owner.pulse(now)
                meals shouldBe 0
                h.usingMainHand shouldBe false
                CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 2
                npc.teleport(target)
                for (now in 9_000L..13_000L step 1_000) h.owner.pulse(now)
                meals shouldBe 1
                h.city.workers.single().hunger shouldBe 20
                CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1
                h.city.stock shouldBe Stock()
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("an unavailable native tavern uses only carried emergency food then retries the venue") {
        residentRuntime { h ->
            try {
                val npc = h.prepareDiningSeat()
                for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
                h.city = h.city.copy(workers = h.city.workers.filter { it.id == h.workerId }, stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL))
                npc.teleport(Location(h.world, .5, 64.0, .5))
                val hall = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }
                val tavern = h.city.buildings.first { it.type == BuildingType.TAVERN }
                val control = requireNotNull(tavern.control)
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.AIR
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    plan.buildingId shouldBe hall.id
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                for (now in 3_000L..8_000L step 1_000) h.owner.pulse(now)
                meals shouldBe 1
                CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1
                h.city.stock shouldBe Stock()
                h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
                h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14) }, revision = h.city.revision + 1)
                h.owner.pulse(35_000)
                withClue(h.owner.debug(h.city.id, h.workerId)) { h.owner.status(h.workerId) shouldBe "workers.status.moving-to-tavern" }
                meals shouldBe 1
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("a tavern route deadline falls back to carried food instead of restarting a hungry route forever") {
        residentRuntime { h ->
            try {
                val npc = h.prepareDiningSeat()
                for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
                h.city = h.city.copy(workers = h.city.workers.filter { it.id == h.workerId }, stock = Stock(),
                    inventory = CivInventory(CivStorageMode.PHYSICAL))
                npc.teleport(Location(h.world, .5, 64.0, .5))
                var meals = 0
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    h.city.building(plan.buildingId)!!.type shouldBe BuildingType.TOWN_HALL
                    h.city = CivsResidentLife.finishMeal(h.city, plan)
                    meals++
                    CompletableFuture.completedFuture(h.city)
                }
                h.owner.pulse(3_000)
                for (now in 4_000L..80_000L step 1_000) h.owner.pulse(now)
                meals shouldBe 1
                CivsEquipment.plainCount(h.city.workers.single(), "BREAD") shouldBe 1
                h.city.stock shouldBe Stock()
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }

    test("an accepted guest meal continues after its optional keeper is reassigned to an accepted building") {
        residentRuntime { h ->
            try {
                val keeper = h.standingTavernkeeper()
                for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
                val guest = h.owner.collisionActors().single { it.uniqueId == h.workerId }
                guest.teleport(Location(h.world, .5, 64.0, .5))
                var proof: CivResidentMeal? = null
                val pending = CompletableFuture<Settlement>()
                h.owner.finishResidentMeal = { cityId, plan ->
                    h.owner.validateResidentMeal(cityId, plan)
                    proof = plan
                    pending
                }
                h.owner.residentLifeEnabled = true
                h.owner.pulse(5_000)
                guest.teleport(requireNotNull(h.routeTarget()))
                for (now in 6_000L..10_000L step 1_000) h.owner.pulse(now)
                h.mealState()["pending"] shouldBe true
                val hut = h.acceptTavernConstruction()
                h.owner.canReassign(h.city.id, keeper) shouldBe true
                h.city = CivsRules.autoAssign(h.city, h.city.owner, 10_000) {
                    it == keeper && h.owner.canReassign(h.city.id, it)
                }
                h.city.workers.single { it.id == keeper }.assignment shouldBe hut.id
                h.city = CivsResidentLife.finishMeal(h.city, requireNotNull(proof))
                pending.complete(h.city)
                h.owner.pulse(11_000)
                CivsEquipment.plainCount(h.city.workers.single { it.id == h.workerId }, "BREAD") shouldBe 1
                h.city.workers.single { it.id == h.workerId }.life!!.meals shouldBe 1L
                h.city.stock.food shouldBe 2L
                h.deliveries shouldBe 0
            } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
        }
    }
})
