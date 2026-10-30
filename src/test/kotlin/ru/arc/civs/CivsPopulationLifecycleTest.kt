package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPopulationLifecycleTest : FunSpec({
    fun id(n: Int) = UUID(0, n.toLong())
    fun city(): Settlement {
        fun building(n: Int, type: BuildingType) = CivBuilding(id(n), type, 3,
            CivPosition(n * 6, 64, 0), 0, "test", CivBox(CivPosition(n * 6 - 1, 63, -1), CivPosition(n * 6 + 1, 69, 1)),
            CivPosition(n * 6, 64, 2), BuildingPhase.ACTIVE)
        return CivsRules.validate(Settlement(id(50), id(51), "Lifecycle", id(52), CivPosition(0, 64, 0),
            128, "test", Stock(), listOf(building(1, BuildingType.TOWN_HALL)) +
                (2..4).map { building(it, BuildingType.HOUSE) }, (100..111).map {
                    CivWorker(id(it), "Resident $it", null, sex = if (it % 2 == 0) CivSex.MALE else CivSex.FEMALE)
                }))
    }
    fun packedCity(): Settlement {
        val base = city()
        val houses = base.buildings.filter { it.type == BuildingType.HOUSE }.map { it.copy(level = 2) }
        val tavern = CivBuilding(id(5), BuildingType.TAVERN, 2, CivPosition(30, 64, 0), 0, "test",
            CivBox(CivPosition(29, 63, -1), CivPosition(31, 69, 1)), CivPosition(30, 64, 2), BuildingPhase.ACTIVE)
        return CivsRules.validate(base.copy(buildings = listOf(base.buildings.first()) + houses + tavern,
            workers = base.workers.mapIndexed { index, worker -> worker.copy(homeId = houses[index / 4].id, hunger = 20,
                assignment = tavern.id.takeIf { index == 2 },
                nextDeliveryAt = if (index == 2) 1L else 0L,
                skills = mapOf("FARM" to 3L),
                parentIds = setOf(id(100), id(101)).takeIf { index == 3 },
                items = List(CivsEquipment.SLOTS) { slot -> if (slot == CivsEquipment.BAG) CivResidentItem("BREAD", 2) else null }) },
            families = houses.mapIndexed { index, home -> CivFamily(id(100 + index * 4), id(101 + index * 4), home.id, 0) }))
    }
    test("packed level two family homes reserve natural child beds by moving only singles into existing communal beds") {
        val before = packedCity()
        val first = CivsPopulation.advance(before, emptySet(), { _, _, _ -> "Child" }, { id(200) })
        val moved = first.workers.filter { worker -> worker.homeId != before.workers.single { it.id == worker.id }.homeId }
        moved.size shouldBe 1
        moved.single().homeId shouldBe id(5)
        first.workers.forEach { worker ->
            val previous = before.workers.single { it.id == worker.id }
            worker.copy(homeId = previous.homeId) shouldBe previous
        }
        var current = first
        var childId = 200
        repeat(CivsPopulation.BIRTH_MINUTES - 1) {
            current = CivsPopulation.advance(current, emptySet(), { _, _, _ -> "Child" }, { id(childId++) })
        }
        current.workers.count(CivsResidents::isChild) shouldBe 1
        current = CivsPopulation.advance(current, emptySet(), { _, _, _ -> "Child" }, { id(childId++) })
        current.workers.size shouldBe 14
        current.workers.count(CivsResidents::isChild) shouldBe 2
        current.workers.count { !CivsResidents.isChild(it) } shouldBe 12
        current.workers.filter(CivsResidents::isChild).forEach { child ->
            val parents = child.parentIds!!.map { parent -> current.workers.single { it.id == parent } }
            parents.all { it.homeId == child.homeId } shouldBe true
        }
        before.families!!.forEach { family ->
            current.workers.single { it.id == family.firstParentId }.homeId shouldBe family.homeId
            current.workers.single { it.id == family.secondParentId }.homeId shouldBe family.homeId
        }
        current.workers.filter { !CivsResidents.isChild(it) }.forEach { worker ->
            val previous = before.workers.single { it.id == worker.id }
            worker.copy(homeId = previous.homeId) shouldBe previous
        }
        CivsRules.validate(Gson().fromJson(Gson().toJson(current), Settlement::class.java)) shouldBe current
    }
    test("a full communal hall never evicts a single or makes anyone homeless") {
        val packed = packedCity()
        val hall = packed.buildings.first { it.type == BuildingType.TOWN_HALL }
        val homes = packed.buildings.filter { it.type == BuildingType.HOUSE }
        val before = CivsRules.validate(packed.copy(buildings = listOf(hall) + homes.mapIndexed { index, home ->
            if (index == 0) home else home.copy(level = 1, phase = BuildingPhase.SITE)
        }, workers = packed.workers.mapIndexed { index, worker -> worker.copy(assignment = null,
            homeId = when { index < 4 -> homes[0].id; index < 9 -> hall.id; else -> homes[1].id }) },
            families = packed.families!!.take(1)))
        val after = CivsPopulation.advance(before, emptySet(), { _, _, _ -> "Child" })
        after.workers shouldBe before.workers
        after.workers.none { it.homeId == null } shouldBe true
        after.workers.count(CivsResidents::isChild) shouldBe 0
    }
    test("housing repair keeps a surviving unpaired parent with their young child") {
        val packed = packedCity()
        val home = packed.families!!.first().homeId
        val child = CivWorker(id(300), "Young child", null, sex = CivSex.MALE, homeId = home,
            parentIds = setOf(id(102), id(900)), childMinutes = 0, hunger = 20)
        val before = CivsRules.validate(packed.copy(workers = packed.workers.map { worker ->
            if (worker.id == id(103)) worker.copy(homeId = id(5)) else worker
        } + child))
        val after = CivsPopulation.advance(before, emptySet(), { _, _, _ -> "Child" })
        for (resident in listOf(id(100), id(101), id(102), child.id)) {
            after.workers.single { it.id == resident } shouldBe before.workers.single { it.id == resident }
        }
        after.workers.single { it.id == id(102) }.homeId shouldBe child.homeId
        after.workers.count(CivsResidents::isChild) shouldBe 1
    }
    test("twelve existing adults admit two visible children without increasing workforce or builder credit") {
        var nextId = 200
        var current = city()
        repeat(CivsPopulation.BIRTH_MINUTES + 1) {
            current = CivsPopulation.advance(current, emptySet(), { _, _, _ -> "Child" }, { id(nextId++) })
        }
        current.workers.size shouldBe 14
        current.workers.count(CivsResidents::isChild) shouldBe 2
        current.workers.count { !CivsResidents.isChild(it) } shouldBe 12
        CivsRules.MAX_BUILDER_WORK_CREDIT shouldBe 768
        val saved = CivsRules.validate(Gson().fromJson(Gson().toJson(current), Settlement::class.java))
        saved shouldBe current
        val childIds = saved.workers.filter(CivsResidents::isChild).mapTo(hashSetOf()) { it.id }
        repeat(CivsPopulation.ADULT_MINUTES + 2) {
            current = CivsPopulation.advance(current, childIds, { _, _, _ -> "Child" }, { id(nextId++) })
        }
        current.workers.filter(CivsResidents::isChild).all { it.childMinutes == 179 && it.assignment == null } shouldBe true
        val removed = CivsRules.hurtResident(current, current.owner, current.workers.first().id, 100.0)
        val replacement = CivsPopulation.advance(removed, childIds, { _, _, _ -> "Child" }, { id(nextId++) })
        replacement.workers.count { !CivsResidents.isChild(it) } shouldBe 12
        replacement.workers.count { it.id in childIds && !CivsResidents.isChild(it) } shouldBe 1
    }
    test("legacy awake history does not become age and dormant residents never age") {
        val legacy = city().workers.first().copy(life = CivResidentLife(awakeMinutes = 100_000))
        CivsResidents.activeMinute(legacy, false) shouldBe legacy
        val adopted = CivsResidents.activeMinute(legacy, true)
        adopted.life!!.awakeMinutes shouldBe 100_001
        adopted.life!!.adultMinutes shouldBe 1
        val saved = CivsRules.validate(city().copy(workers = listOf(adopted)))
        CivsPopulation.retireElder(saved, mapOf(adopted.id to CivDeathContext(saved.center, 1, cause = "OLD_AGE"))) shouldBe saved
    }
    test("every adult lifespan is at least seven full active days with stable bounded variation") {
        repeat(1_000) { n ->
            val worker = CivWorker(UUID(n.toLong() * -123_456_789L, n.toLong() * 987_654_321L), "Adult", null)
            val lifespan = CivsResidentLife.lifespanMinutes(worker)
            (lifespan in 7 * 24 * 60L..10 * 24 * 60L) shouldBe true
            CivsResidentLife.lifespanMinutes(worker.copy(name = "Renamed", completedCycles = 99)) shouldBe lifespan
        }
    }
    test("old age needs fresh safe actor proof retires one resident and preserves corpse inventory and ancestry") {
        val base = city()
        val aged = base.copy(workers = base.workers.map { worker -> worker.copy(
            life = CivResidentLife(awakeMinutes = CivsResidentLife.lifespanMinutes(worker) + 100,
                adultMinutes = CivsResidentLife.lifespanMinutes(worker)),
            items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
                CivsEquipment.BAG -> CivResidentItem("BREAD", 2)
                CivsEquipment.BAG + 1 -> CivResidentItem("IRON_INGOT", 3)
                else -> null
            } }) })
        CivsRules.validate(aged)
        CivsPopulation.retireElder(aged, emptyMap()) shouldBe aged
        val proof = aged.workers.take(2).associate { it.id to CivDeathContext(aged.center, 10_000, cause = "OLD_AGE") }
        val retired = CivsPopulation.retireElder(aged, proof)
        retired.workers.size shouldBe 11
        val corpse = retired.corpses!!.single()
        corpse.worker shouldBe aged.workers.first()
        corpse.expiresAt - corpse.diedAt shouldBe CivsCorpses.LIFETIME_MILLIS
        CivsRules.validate(Gson().fromJson(Gson().toJson(retired), Settlement::class.java)) shouldBe retired
        val revived = CivsRules.resurrect(retired, retired.owner, corpse.worker.id, corpse.diedAt, corpse.diedAt + 1)
        revived.workers.last().life!!.adultMinutes shouldBe 0
        revived.workers.last().items shouldBe corpse.worker.items
        CivsPopulation.retireElder(revived, proof).workers.any { it.id == corpse.worker.id } shouldBe true
    }
})
