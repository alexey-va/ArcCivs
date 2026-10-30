package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsResidentLifeTest : FunSpec({
    test("old JSON retains null life and only observed awake residents use the same hunger clock") {
        val city = lifeCity()
        val worker = city.workers.first()
        Gson().toJsonTree(worker).asJsonObject.has("life") shouldBe false
        Gson().fromJson(Gson().toJson(worker), CivWorker::class.java) shouldBe worker
        var current = worker.copy(assignment = null, inventory = mapOf("BREAD" to 2L))
        repeat(6) { current = CivsResidents.activeMinute(current, true) }
        CivsResidents.hunger(current) shouldBe 14
        current.life!!.awakeMinutes shouldBe 6L
        CivsEquipment.plainCount(current, "BREAD") shouldBe 2
        repeat(60) { current = CivsResidents.activeMinute(current, false) }
        current.life!!.awakeMinutes shouldBe 6L
        CivsResidents.hunger(current) shouldBe 14
    }

    for (autonomous in listOf(false, true))
    test("an observed meal transfers no work credit and consumes one existing food unit with Auto $autonomous") {
        val initial = lifeCity(tavern = true)
        val worker = initial.workers.first().copy(hunger = 0,
            inventory = mapOf("BREAD" to 2L), life = CivResidentLife(awakeMinutes = 10))
        val before = CivsRules.validate(initial.copy(workers = listOf(worker) + initial.workers.drop(1),
            inventory = if (autonomous) null else initial.inventory,
            autonomy = if (autonomous) CivAutonomy() else null, stock = if (autonomous) Stock(10, 20, 3) else Stock()))
        val plan = requireNotNull(CivsResidentLife.mealPlan(before, worker))
        before.building(plan.buildingId)!!.type shouldBe BuildingType.TAVERN
        plan.source shouldBe CivResidentMealSource.CARRIED_BREAD
        plan.amount shouldBe 2
        // Another worker's committed action does not invalidate this independent meal.
        val concurrent = before.copy(revision = before.revision + 7)
        // A full or unfinished dining room can use the actual hall; an arbitrary home cannot.
        val hall = before.buildings.first { it.type == BuildingType.TOWN_HALL }
        val fallback = requireNotNull(CivsResidentLife.mealPlan(before, worker, hall.id))
        CivsResidents.hunger(CivsResidentLife.finishMeal(concurrent, fallback).workers.first()) shouldBe 12
        val house = before.buildings.first { it.type == BuildingType.HOUSE }
        CivsResidentLife.mealPlan(before, worker, house.id) shouldBe null
        shouldThrow<CivsRejection> {
            CivsResidentLife.finishMeal(concurrent, fallback.copy(buildingId = house.id))
        }.reason shouldBe "stale-meal"
        val after = CivsResidentLife.finishMeal(concurrent, plan)
        val fed = after.workers.first()
        CivsResidents.hunger(fed) shouldBe 12
        fed.life!!.meals shouldBe 2L
        fed.life!!.lastMealMinute shouldBe 10L
        fed.copy(hunger = worker.hunger, inventory = worker.inventory, items = worker.items, life = worker.life) shouldBe worker
        after.stock shouldBe before.stock
        CivsEquipment.plainCount(fed, "BREAD") shouldBe 0
        shouldThrow<CivsRejection> { CivsResidentLife.finishMeal(after, plan) }.reason shouldBe "stale-meal"
        val unstaffed = before.copy(workers = listOf(worker) + before.workers.drop(1).map { it.copy(assignment = null) })
        unstaffed.building(requireNotNull(CivsResidentLife.mealPlan(unstaffed, worker)).buildingId)!!.type shouldBe BuildingType.TAVERN
        CivsResidentLife.mealPlan(before.copy(workers = before.workers.map {
            if (it.id == worker.id) it.copy(hunger = 15) else it
        }), worker.copy(hunger = 15)) shouldBe null
    }

    test("a custom or shipment-reserved bread is never a meal and missing Auto FOOD never creates one") {
        val base = lifeCity()
        val worker = base.workers.first()
        val items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }
        items[CivsEquipment.BAG] = CivResidentItem("BREAD", 2, "AA==")
        val hungry = worker.copy(hunger = 10, items = items)
        val city = base.copy(workers = listOf(hungry))
        CivsResidentLife.mealPlan(city, hungry) shouldBe null
        CivsResidentLife.mealPlan(city.copy(inventory = null, autonomy = CivAutonomy(), stock = Stock()), hungry) shouldBe null
    }

    test("UUID aptitude accelerates only matching profession learning under the current home cap") {
        val base = lifeCity()
        val type = BuildingType.FORESTER
        val match = (1L..100L).map { UUID(0, it) }.first {
            CivsResidentLife.aptitude(base.workers.first().copy(id = it)) == type
        }
        val worker = base.workers.first().copy(id = match, skills = null, life = CivResidentLife())
        CivsResidentLife.aptitude(Gson().fromJson(Gson().toJson(worker), CivWorker::class.java)) shouldBe type
        for (level in 1..3) {
            val city = base.copy(buildings = base.buildings.map { if (it.id == worker.homeId) it.copy(level = level) else it },
                workers = listOf(worker))
            var trained = worker
            repeat(30) { trained = CivsResidents.trained(trained, type, city) }
            trained.skills!![type.name] shouldBe (level + 1) * 10L
            trained.completedCycles shouldBe worker.completedCycles
            trained.nextDeliveryAt shouldBe worker.nextDeliveryAt
            CivsResidents.workDuration(trained, 2_000) shouldBe 2_000L
            CivsResidents.maxHealth(trained) shouldBe 20.0 + 2 * ((level + 1))
        }
        val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
        val communal = worker.copy(homeId = hall.id)
        CivsResidentLife.learningCap(base, communal) shouldBe 10L
        CivsResidents.trained(communal, type, base).skills!![type.name] shouldBe 2L
        CivsResidents.trained(communal, BuildingType.QUARRY, base).skills!!["QUARRY"] shouldBe 1L
        val historic = communal.copy(skills = mapOf(type.name to 40L))
        CivsResidents.trained(historic, type, base) shouldBe historic
        CivsResidents.trained(communal, type).skills!![type.name] shouldBe 1L
    }

    test("singles move out of the hall into available houses while paired parents retain their home") {
        val base = lifeCity()
        val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
        val worker = base.workers.first().copy(homeId = hall.id, sex = CivSex.MALE)
        val other = worker.copy(id = UUID(0, 90), name = "Other", assignment = null)
        val city = base.copy(workers = listOf(worker, other))
        val housed = lifeMinute(city, emptySet())
        housed.workers.all { it.homeId == worker.homeId || it.homeId == base.buildings.first { b -> b.type == BuildingType.HOUSE }.id } shouldBe true
        housed.workers.none { it.homeId == hall.id } shouldBe true
        val female = other.copy(sex = CivSex.FEMALE)
        val paired = lifeMinute(city.copy(workers = listOf(worker, female)), emptySet())
        val family = paired.families!!.single()
        val moved = lifeMinute(paired, emptySet())
        moved.families!!.single().homeId shouldBe family.homeId
        moved.workers.map { it.homeId } shouldBe paired.workers.map { it.homeId }
    }

    test("a legacy forestry receipt above the new home cap keeps its exact old training through the real journal codec") {
        val base = lifeCity()
        val hall = base.buildings.first { it.type == BuildingType.TOWN_HALL }
        val worker = base.workers.first().copy(homeId = hall.id, skills = mapOf("FORESTER" to 22L), life = null)
        val city = CivsRules.validate(base.copy(workers = listOf(worker), inventory = null, autonomy = CivAutonomy(), stock = Stock(wood = 10)))
        CivsResidentLife.learningCap(city, worker) shouldBe 10L
        val building = requireNotNull(city.building(requireNotNull(worker.assignment)))
        val timber = requireNotNull(CivsProduction.material(building))
        val sapling = timber.removeSuffix("_LOG") + "_SAPLING"
        val roots = listOf(CivPosition(40, 64, 0), CivPosition(40, 64, 1), CivPosition(41, 64, 0), CivPosition(41, 64, 1))
            .take(if (CivsForestry.large(timber)) 4 else 1)
        val work = CivWorldWork(worker.id, worker.completedCycles, building.id, roots.first().copy(x = 39),
            roots.map { CivPlanBlock(it, "minecraft:air", CivsForestry.saplingState(sapling)) },
            inputs = mapOf(sapling to roots.size.toLong()), status = "planting-tree", focus = roots.first())
        val after = CivsRules.finishWorldWork(city, work, 1_000)
        after.workers.single().skills shouldBe mapOf("FORESTER" to 23L)
        val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
            worldChanges = work.changes, forestry = CivForestryReceipt(worker, city.stock, null, true, work, 1_000)).validated()
        val root = Files.createTempDirectory("arc-civs-life-legacy-")
        try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.save(city).join()
                store.saveInventoryTransaction(receipt).join() shouldBe receipt
            }
            CivsStore(root, Executor { it.run() }).use { store ->
                val restored = store.loadInventoryTransactions().join().single().validated()
                restored shouldBe receipt
                restored.forestry!!.workerBefore.life shouldBe null
                restored.after.workers.single().skills shouldBe mapOf("FORESTER" to 23L)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("needs expose food home rest employment family and protection causes without mistaking a route delay for joblessness") {
        val base = lifeCity()
        val worker = base.workers.first().copy(life = CivResidentLife(awakeMinutes = 35))
        val occupied = CivsResidentLife.needs(base, worker)
        (CivResidentNeed.OCCUPIED in occupied.causes) shouldBe true
        (CivResidentNeed.JOBLESS in occupied.causes) shouldBe false
        (CivResidentNeed.REST_NEEDED in occupied.causes) shouldBe true
        val city = base.copy(workers = listOf(worker))
        val rested = CivsResidentLife.finishRest(city, worker.id, 35)
        val needs = CivsResidentLife.needs(rested, rested.workers.first())
        (CivResidentNeed.RESTED in needs.causes) shouldBe true
        (needs.happiness > occupied.happiness) shouldBe true
        rested.stock shouldBe city.stock
        rested.workers.first().skills shouldBe worker.skills
        shouldThrow<CivsRejection> { CivsResidentLife.finishRest(rested, worker.id, 35) }.reason shouldBe "stale-rest"
        val distressed = CivsResidentLife.needs(city, worker.copy(hunger = 3, homeId = null, assignment = null))
        distressed.causes.containsAll(listOf(CivResidentNeed.HUNGRY, CivResidentNeed.HOMELESS, CivResidentNeed.JOBLESS)) shouldBe true
    }

    test("co-located mentorship records at most ten education XP and transfers it exactly once on the fortieth awake child minute") {
        var city = lifeCity(child = true)
        val childId = city.workers.last().id
        val teacherId = city.workers.first().id
        for (minute in 1..10) {
            val child = city.workers.first { it.id == childId }
            val proof = lesson(city, child, teacherId)
            if (minute == 1) shouldThrow<CivsRejection> {
                CivsResidentLife.finishLesson(city, proof.copy(teacherPosition = proof.teacherPosition.copy(x = 70)))
            }.reason shouldBe "stale-lesson"
            city = CivsResidentLife.finishLesson(city.copy(revision = city.revision + 2), proof)
            val educated = city.workers.first { it.id == childId }
            educated.skills shouldBe null
            educated.assignment shouldBe null
            educated.items shouldBe null
            educated.completedCycles shouldBe 0L
            educated.life!!.education!!.values.sum() shouldBe minute.toLong()
            shouldThrow<CivsRejection> { CivsResidentLife.finishLesson(city, proof) }.reason shouldBe "stale-lesson"
            if (minute < 10) city = lifeMinute(city, setOf(childId))
        }
        val atCap = city.workers.first { it.id == childId }
        city = lifeMinute(city, setOf(childId))
        shouldThrow<CivsRejection> { CivsResidentLife.finishLesson(city, lesson(city, city.workers.last(), teacherId)) }
            .reason shouldBe "education-complete"
        repeat(50) { city = lifeMinute(city, emptySet()) }
        city.workers.first { it.id == childId }.childMinutes shouldBe atCap.childMinutes!! + 1
        repeat(CivsPopulation.ADULT_MINUTES - requireNotNull(city.workers.last().childMinutes)) {
            city = lifeMinute(city, setOf(childId))
        }
        val adult = city.workers.first { it.id == childId }
        adult.childMinutes shouldBe null
        adult.skills shouldBe mapOf("FORESTER" to 10L)
        adult.life!!.education shouldBe null
        adult.life!!.lastLessonAge shouldBe null
        repeat(5) { city = lifeMinute(city, setOf(childId)) }
        city.workers.first { it.id == childId }.skills shouldBe adult.skills
    }

    test("forty awake child minutes use half an adult's hunger and at most three existing meals") {
        val base = lifeCity(child = true)
        val child = base.workers.last().copy(childMinutes = 0, life = null, inventory = mapOf("BREAD" to 3L))
        var city = base.copy(workers = listOf(base.workers.first(), child))
        repeat(40) {
            city = lifeMinute(city, setOf(child.id))
            CivsResidentLife.mealPlan(city, city.workers.last())?.let { plan -> city = CivsResidentLife.finishMeal(city, plan) }
        }
        val grown = city.workers.last()
        grown.childMinutes shouldBe 40
        grown.life!!.meals shouldBe 3L
        CivsResidents.hunger(grown) shouldBe 18
        CivsEquipment.plainCount(grown, "BREAD") shouldBe 0
        grown.skills shouldBe null
    }

    test("life validation rejects unbounded or premature skill history and queued saves detach education") {
        val city = lifeCity(child = true)
        val child = city.workers.last()
        val education = linkedMapOf("FORESTER" to 1L)
        val valid = child.copy(life = requireNotNull(child.life).copy(lastLessonAge = 1, education = education))
        val savedCity = city.copy(workers = listOf(city.workers.first(), valid))
        val expected = savedCity.copy(workers = savedCity.workers.map { worker -> worker.copy(life = worker.life?.let {
            it.copy(education = it.education?.toMap())
        }) })
        val pending = ArrayDeque<Runnable>()
        val root = Files.createTempDirectory("arc-civs-life-")
        try {
            CivsStore(root, Executor { pending.addLast(it) }).use { store ->
                val saved = store.save(savedCity)
                education["FORESTER"] = 11
                pending.removeFirst().run()
                saved.join() shouldBe expected
            }
            CivsStore(root, Executor { it.run() }).use { it.load().join().single() shouldBe expected }
        } finally { root.toFile().deleteRecursively() }
        for (life in listOf(CivResidentLife(awakeMinutes = -1), CivResidentLife(awakeMinutes = 1_000_000_001),
            CivResidentLife(lastMealMinute = 1), CivResidentLife(lastLessonAge = 39),
            CivResidentLife(education = mapOf("FORESTER" to 11L)))) {
            shouldThrow<CivsRejection> { CivsRules.validate(city.copy(workers = listOf(city.workers.first(), child.copy(life = life)))) }
        }
    }
})

private fun lifeCity(tavern: Boolean = false, child: Boolean = false): Settlement {
    fun building(id: Long, type: BuildingType, x: Int) = CivBuilding(UUID(0, id), type, 1,
        CivPosition(x, 64, 0), 0, "test", CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 68, 2)),
        CivPosition(x, 64, 3), BuildingPhase.ACTIVE, control = CivPosition(x, 64, 2))
    val hall = building(1, BuildingType.TOWN_HALL, 0).copy(level = 3)
    val home = building(2, BuildingType.HOUSE, 10)
    val work = building(3, BuildingType.FORESTER, 20)
    val inn = building(4, BuildingType.TAVERN, 30)
    val teacher = CivWorker(UUID(0, 100), "Teacher", work.id, nextDeliveryAt = 1, skills = mapOf("FORESTER" to 10L),
        sex = CivSex.MALE, homeId = home.id, hunger = 20)
    val children = if (child) listOf(CivWorker(UUID(0, 101), "Child", null, sex = CivSex.FEMALE, homeId = home.id,
        parentIds = setOf(teacher.id, UUID(0, 999)), childMinutes = 1, life = CivResidentLife(awakeMinutes = 1))) else emptyList()
    val innkeeper = if (tavern) listOf(teacher.copy(id = UUID(0, 102), name = "Innkeeper", assignment = inn.id, skills = null)) else emptyList()
    return CivsRules.validate(Settlement(UUID(0, 10), UUID(0, 20), "Life", UUID(0, 30), CivPosition(0, 64, 0),
        80, "test", Stock(), listOf(hall, home, work) + if (tavern) listOf(inn) else emptyList(),
        listOf(teacher) + children + innkeeper, inventory = CivInventory(CivStorageMode.VIRTUAL)))
}

private fun lifeMinute(city: Settlement, awake: Set<UUID>): Settlement =
    CivsPopulation.advance(city, awake, { _, id, _ -> "Resident ${id.leastSignificantBits}" })

private fun lesson(city: Settlement, child: CivWorker, teacherId: UUID) = CivResidentLessonProof(city.revision,
    child.id, teacherId, BuildingType.FORESTER, requireNotNull(child.childMinutes), requireNotNull(child.life).awakeMinutes,
    CivPosition(10, 64, 0), CivPosition(12, 64, 0))
