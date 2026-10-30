package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPopulationTest : FunSpec({
    test("legacy adults acquire stable homes and existing voice sex without items names or offline age changes") {
        val f = PopulationFixture(houses = 0)
        val legacy = f.city.copy(workers = f.city.workers.map { it.copy(sex = null, hunger = null) })
        val decoded = CivsRules.validate(Gson().fromJson(Gson().toJson(legacy), Settlement::class.java))
        decoded shouldBe legacy
        val next = f.minute(decoded)
        next.workers.map { it.name } shouldBe legacy.workers.map { it.name }
        next.workers.map { it.sex } shouldBe legacy.workers.map { CivsResidents.legacySex(it.id) }
        next.workers.all { it.homeId == next.buildings.first().id && !CivsResidents.isChild(it) } shouldBe true
        next.workers.all { it.inventory == null && it.items == null && it.hunger == 20 } shouldBe true
        next.stock shouldBe legacy.stock
        next.inventory shouldBe legacy.inventory
        val saved = CivsRules.validate(Gson().fromJson(Gson().toJson(next), Settlement::class.java))
        saved shouldBe next
        f.minute(saved) shouldBe saved
    }

    test("immigration refills to five one at a time and cannot bypass family growth or mint supplies") {
        val f = PopulationFixture(houses = 0)
        var city = f.city.copy(workers = emptyList(), immigration = null, createdAt = 1_000)
        CivsRules.immigrationDue(city, 30_999) shouldBe false
        CivsRules.immigrationDue(city, 31_000) shouldBe true
        repeat(5) { index ->
            val now = 31_000L + index * 60_000
            city = CivsRules.immigrate(city, city.owner, popId(100 + index), "Arrival $index", now)
            city.workers.size shouldBe index + 1
            CivsRules.immigrationDue(city, now) shouldBe false
            city.stock shouldBe f.city.stock
            city.workers.last().items shouldBe null
            city.workers.last().inventory shouldBe null
        }
        city.workers.map(CivsResidents::sex) shouldBe listOf(CivSex.MALE, CivSex.FEMALE, CivSex.MALE, CivSex.FEMALE, CivSex.MALE)
        CivsRules.immigrate(city, city.owner, popId(999), "Sixth", 1_000_000) shouldBe city
        shouldThrow<CivsRejection> { CivsRules.recruit(city, city.owner, popId(999), "Paid") }.reason shouldBe "population-natural"
        shouldThrow<CivsRejection> { CivsRules.recruitFunded(city, city.owner, popId(999), "Paid") }.reason shouldBe "population-natural"
        val dead = city.workers.first()
        city = CivsRules.hurtResident(city, city.owner, dead.id, 100.0)
        CivsRules.immigrationDue(city, 1_000_000) shouldBe true
        val replacement = CivsRules.immigrate(city, city.owner, popId(999), "Replacement", 1_000_000)
        replacement.workers.size shouldBe 5
        replacement.stock shouldBe city.stock
        replacement.workers.last().sex shouldBe CivSex.MALE
    }

    test("two houses form stable unrelated pairs and admit at most one birth per active minute") {
        val f = PopulationFixture()
        var city = f.minute(f.city)
        city.families!!.size shouldBe 2
        val pairs = city.families!!.map { it.copy(activeMinutes = 0) }
        repeat(18) { city = f.minute(city) }
        city.workers.size shouldBe 5
        city.families!!.all { it.activeMinutes == 19 } shouldBe true
        city = f.minute(city)
        city.workers.size shouldBe 6
        city.workers.count(CivsResidents::isChild) shouldBe 1
        city.families!!.map { it.copy(activeMinutes = 0) } shouldBe pairs
        city.families!!.map { it.activeMinutes } shouldBe listOf(0, 20)
        city = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
        city = f.minute(city)
        city.workers.size shouldBe 7
        city.workers.count(CivsResidents::isChild) shouldBe 2
        city.workers.filter(CivsResidents::isChild).all {
            it.assignment == null && it.cargo == null && it.items == null && it.inventory == null &&
                it.parentIds?.size == 2 && it.homeId in city.families!!.map(CivFamily::homeId)
        } shouldBe true
        repeat(10) { city = f.minute(city) }
        city.workers.size shouldBe 7 // Three beds in each house are occupied.
        city.workers.groupingBy { it.homeId }.eachCount().values.sorted() shouldBe listOf(1, 3, 3)
    }

    test("child matures after three observed active hours and cannot work before then") {
        val f = PopulationFixture(houses = 1)
        var city = f.city
        repeat(20) { city = f.minute(city) }
        val childId = city.workers.single(CivsResidents::isChild).id
        val home = city.workers.first { it.id == childId }.homeId
        shouldThrow<CivsRejection> { CivsRules.assign(city, city.owner, childId, city.buildings.first().id, 100_000) }
            .reason shouldBe "child-cannot-work"
        val assigned = CivsRules.autoAssign(city, city.owner, 100_000)
        assigned.workers.first { it.id == childId }.assignment shouldBe null
        repeat(CivsPopulation.ADULT_MINUTES - 1) { city = f.minute(city) }
        city.workers.first { it.id == childId }.childMinutes shouldBe CivsPopulation.ADULT_MINUTES - 1
        val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
        reloaded.workers.first { it.id == childId }.childMinutes shouldBe CivsPopulation.ADULT_MINUTES - 1
        city = f.minute(reloaded)
        val adult = city.workers.first { it.id == childId }
        CivsResidents.isChild(adult) shouldBe false
        adult.homeId shouldBe home
        adult.parentIds!!.size shouldBe 2
        val working = CivsRules.assign(city, city.owner, childId, city.buildings.first().id, 100_000)
        working.workers.first { it.id == childId }.assignment shouldBe city.buildings.first().id
    }

    test("houses beds town hall cap and current parents gate family births") {
        val f = PopulationFixture()
        var capped = f.city.copy(buildings = f.city.buildings.map { it.copy(level = 1) })
        repeat(45) { capped = f.minute(capped) }
        capped.workers.size shouldBe 5
        capped.families!!.all { it.activeMinutes == 0 } shouldBe true
        CivsRules.populationLimit(capped) shouldBe 5
        CivsRules.beds(capped) shouldBe 11
        var sites = f.city.copy(buildings = f.city.buildings.map {
            if (it.type == BuildingType.HOUSE) it.copy(phase = BuildingPhase.SITE) else it
        })
        repeat(25) { sites = f.minute(sites) }
        sites.families shouldBe null
        sites.workers.size shouldBe 5
        CivsRules.beds(sites) shouldBe 5

        var family = f.city
        repeat(20) { family = f.minute(family) }
        val child = family.workers.single(CivsResidents::isChild)
        val deceased = child.parentIds!!.first()
        val after = CivsRules.hurtResident(family, family.owner, deceased, 100.0)
        after.workers.first { it.id == child.id }.parentIds shouldBe child.parentIds
        after.families!!.none { it.firstParentId == deceased || it.secondParentId == deceased } shouldBe true
        CivsRules.validate(Gson().fromJson(Gson().toJson(after), Settlement::class.java)) shouldBe after
        val removed = CivsRules.finishBuildingDeletion(CivsRules.requestDeletion(after, after.owner, child.homeId), after.owner)
        removed.workers.first { it.id == child.id }.homeId shouldBe null
        removed.families!!.none { it.homeId == child.homeId } shouldBe true
    }

    test("siblings and parent-child pairs are rejected even when a parent is no longer alive") {
        val f = PopulationFixture(houses = 1)
        val familyIds = setOf(popId(900), popId(901))
        var siblings = f.city.copy(workers = f.city.workers.map { it.copy(parentIds = familyIds) })
        repeat(30) { siblings = f.minute(siblings) }
        siblings.families shouldBe null
        siblings.workers.size shouldBe 5
        val parent = f.city.workers[0]
        val child = f.city.workers[1].copy(parentIds = setOf(parent.id, popId(902)))
        CivsPopulation.related(parent, child) shouldBe true
        CivsPopulation.related(child, parent) shouldBe true
        val home = f.city.buildings.last().id
        val bad = f.city.copy(workers = listOf(parent.copy(homeId = home), child.copy(homeId = home)) + f.city.workers.drop(2),
            families = listOf(CivFamily(parent.id, child.id, home, 0)))
        shouldThrow<CivsRejection> { CivsRules.validate(bad) }.reason shouldBe "invalid-state"
    }

    test("only observed awake minutes drain hunger and carried bread waits for an observed meal") {
        var worker = CivWorker(popId(100), "Worker", null, inventory = mapOf("BREAD" to 2L))
        repeat(5) { worker = CivsResidents.activeMinute(worker, true) }
        CivsResidents.hunger(worker) shouldBe 15
        CivsEquipment.plainCount(worker, "BREAD") shouldBe 2
        worker = CivsResidents.activeMinute(worker, true)
        CivsResidents.hunger(worker) shouldBe 14
        CivsEquipment.plainCount(worker, "BREAD") shouldBe 2
        repeat(6) { worker = CivsResidents.activeMinute(worker, true) }
        CivsEquipment.plainCount(worker, "BREAD") shouldBe 2
        repeat(2) { worker = CivsResidents.activeMinute(worker, true) }
        CivsResidents.hunger(worker) shouldBe 6
        CivsResidents.workDuration(worker, 2_000) shouldBe 4_000L
        CivsResidents.workDuration(worker.copy(hunger = 7), 2_000) shouldBe 2_000L
        CivsResidents.workDuration(worker, Long.MAX_VALUE) shouldBe Long.MAX_VALUE
        repeat(100) { worker = CivsResidents.activeMinute(worker, true) }
        CivsResidents.hunger(worker) shouldBe 0
        CivsResidents.health(worker) shouldBe 20.0
        val idle = worker.copy(hunger = 12)
        CivsResidents.activeMinute(idle, false) shouldBe idle
        val child = idle.copy(childMinutes = 0, parentIds = setOf(popId(900), popId(901)))
        val firstChildMinute = CivsResidents.activeMinute(child, true)
        CivsResidents.hunger(firstChildMinute) shouldBe 12
        CivsResidents.hunger(CivsResidents.activeMinute(firstChildMinute, true)) shouldBe 11

        val slots = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }
        slots[4] = CivResidentItem("BREAD", 1)
        slots[6] = CivResidentItem("BREAD", 3, "AA==") // Opaque custom payload must remain untouched.
        val reservedFood = idle.copy(items = slots)
        CivsResidents.activeMinute(reservedFood, false) shouldBe reservedFood
        val city = PopulationFixture(houses = 0).city.copy(workers = listOf(idle.copy(
            items = null, inventory = mapOf("BREAD" to 1L))))
        val fed = CivsRules.feedResident(city, city.owner, idle.id).workers.single()
        CivsResidents.hunger(fed) shouldBe 18
        CivsResidents.health(fed) shouldBe 20.0
        CivsEquipment.plainCount(fed, "BREAD") shouldBe 0
    }

    test("malformed age hunger ancestry and family records fail closed") {
        val f = PopulationFixture(houses = 1)
        val worker = f.city.workers.first()
        for (bad in listOf(worker.copy(hunger = -1), worker.copy(hunger = 21),
            worker.copy(childMinutes = -1), worker.copy(childMinutes = CivsPopulation.ADULT_MINUTES),
            worker.copy(parentIds = setOf(worker.id, popId(999))),
            worker.copy(homeId = popId(999)), worker.copy(parentIds = setOf(popId(999))))) {
            shouldThrow<CivsRejection> { CivsRules.validate(f.city.copy(workers = listOf(bad))) }.reason shouldBe "invalid-state"
        }
        val paired = f.minute(f.city)
        for (family in listOf(paired.families!!.single().copy(activeMinutes = -1),
            paired.families!!.single().copy(activeMinutes = 21),
            paired.families!!.single().copy(secondParentId = paired.families!!.single().firstParentId))) {
            shouldThrow<CivsRejection> { CivsRules.validate(paired.copy(families = listOf(family))) }.reason shouldBe "invalid-state"
        }
        val child = worker.copy(childMinutes = 0, parentIds = setOf(popId(900), popId(901)),
            assignment = f.city.buildings.first().id, nextDeliveryAt = 1)
        shouldThrow<CivsRejection> { CivsRules.validate(f.city.copy(workers = listOf(child))) }.reason shouldBe "invalid-state"
    }
})

private fun popId(value: Int) = UUID(0, value.toLong())

private class PopulationFixture(houses: Int = 2) {
    private var nextId = 200
    private val hall = building(1, BuildingType.TOWN_HALL, 0).copy(level = 3)
    val city = CivsRules.validate(Settlement(popId(10), popId(20), "Population", popId(30), CivPosition(0, 64, 0),
        128, "test", Stock(10, 20, 30), listOf(hall) + (1..houses).map { building(1 + it, BuildingType.HOUSE, it * 10) },
        (100..104).map { CivWorker(popId(it), "Resident $it", null, sex = if (it % 2 == 0) CivSex.MALE else CivSex.FEMALE) }))

    fun minute(city: Settlement, awake: Set<UUID> = city.workers.filter(CivsResidents::isChild).mapTo(hashSetOf()) { it.id }): Settlement =
        CivsPopulation.advance(city, awake, { _, id, _ -> "Resident ${id.leastSignificantBits}" }, { popId(nextId++) })

    private fun building(id: Int, type: BuildingType, x: Int) = CivBuilding(popId(id), type, 1,
        CivPosition(x, 64, 0), 0, "test", CivBox(CivPosition(x - 1, 63, -1), CivPosition(x + 1, 68, 1)),
        CivPosition(x, 64, 2), BuildingPhase.ACTIVE)
}
