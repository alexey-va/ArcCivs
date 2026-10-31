package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsMultipleProducerStaffingTest : FunSpec({
    test("twenty four adults staff each resource building and depot before extra slots while children keep their belongings") {
        val base = staffingCity()
        val home = base.buildings.first { it.type == BuildingType.HOUSE }
        val adults = (0 until 24).map { index -> staffingWorker(index).copy(assignment = null, nextDeliveryAt = 0,
            homeId = home.id.takeIf { index < 2 }) }
        val children = (0 until 4).map { index -> CivWorker(UUID(139, 300 + index.toLong()), "Child $index", null,
            sex = CivSex.MALE, homeId = home.id, parentIds = setOf(adults[0].id, adults[1].id), childMinutes = 0, hunger = 20) }
        val before = CivsRules.validate(base.copy(workers = adults + children))
        var current = before
        repeat(24) { current = CivsRules.autoAssign(current, current.owner, 1_000) }
        val jobs = current.buildings.filter { it.type in staffingEssentialTypes }
        jobs.size shouldBe 9
        jobs.all { job -> current.workers.any { it.assignment == job.id } } shouldBe true
        current.workers.filter(CivsResidents::isChild) shouldBe children
        current.workers.filterNot(CivsResidents::isChild).forEach { worker ->
            val original = adults.single { it.id == worker.id }
            worker.copy(assignment = original.assignment, nextDeliveryAt = original.nextDeliveryAt) shouldBe original
        }
        current.stock shouldBe before.stock
        current.inventory shouldBe before.inventory
        current.construction shouldBe null
    }

    test("ordinary recovery preserves one worker in every raw site and depot and borrows only a safe surplus") {
        val base = staffingCity()
        val jobs = base.buildings.filter { it.type in staffingEssentialTypes }
        val workers = jobs.mapIndexed { index, job -> staffingWorker(index).copy(assignment = job.id) }
        val staffed = CivsRules.validate(base.copy(workers = workers))
        // The unstaffed tavern cannot take the sole worker of a second building of the same type.
        CivsAutonomy.criticalAssignment(staffed) { true } shouldBe null
        val target = jobs.last { it.type == BuildingType.FARM }
        val forest = jobs.first { it.type == BuildingType.FORESTER }
        val spare = staffingWorker(50).copy(assignment = forest.id)
        val before = CivsRules.validate(staffed.copy(workers = workers.filterNot { it.assignment == target.id } + spare))
        CivsAutonomy.criticalAssignment(before) { it == spare.id } shouldBe (spare.id to target.id)
        CivsRules.autoAssign(before, before.owner, 1_000) { false } shouldBe before
        val after = CivsRules.autoAssign(before, before.owner, 1_000) { it == spare.id }
        after.workers.single { it.id == spare.id }.assignment shouldBe target.id
        after.workers.filterNot { it.id == spare.id } shouldBe before.workers.filterNot { it.id == spare.id }
        after.workers.single { it.id == spare.id }.copy(assignment = spare.assignment,
            nextDeliveryAt = spare.nextDeliveryAt) shouldBe spare
        after.stock shouldBe before.stock
        after.inventory shouldBe before.inventory
    }

    test("accepted builder recovery may borrow a forester or miner while each last farmer and depot keeper stays") {
        val base = staffingCity()
        val workers = base.buildings.filter { it.type in staffingEssentialTypes }.mapIndexed { index, job ->
            staffingWorker(index).copy(assignment = job.id)
        }
        val house = base.buildings.first { it.type == BuildingType.HOUSE }.copy(level = 1, phase = BuildingPhase.SITE)
        val city = CivsRules.validate(base.copy(workers = workers, constructionQueue = listOf(house.id),
            buildings = base.buildings.map { if (it.id == house.id) house else it }))
        val hut = city.buildings.first { it.type == BuildingType.BUILDER_HUT }
        for (type in listOf(BuildingType.FORESTER, BuildingType.QUARRY)) {
            val worker = workers.last { city.building(requireNotNull(it.assignment))?.type == type }
            CivsAutonomy.criticalAssignment(city) { it == worker.id } shouldBe (worker.id to hut.id)
        }
        for (worker in workers.filter { city.building(requireNotNull(it.assignment))?.type in
            setOf(BuildingType.FARM, BuildingType.WAREHOUSE) })
            CivsAutonomy.criticalAssignment(city) { it == worker.id } shouldBe null
        city.constructionQueue shouldBe listOf(house.id)
        city.workers shouldBe workers
    }
})

private val staffingEssentialTypes = setOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE)
private fun staffingWorker(index: Int) = CivWorker(UUID(139, 100 + index.toLong()), "Adult $index", null,
    completedCycles = 4, nextDeliveryAt = 1, sex = if (index % 2 == 0) CivSex.MALE else CivSex.FEMALE,
    hunger = 20, skills = mapOf("FARM" to 3L), items = List(CivsEquipment.SLOTS) {
        if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 2) else null
    })

private fun staffingCity(): Settlement {
    val types = listOf(BuildingType.TOWN_HALL) + CivsAutonomy.completeProfile.toMutableList().apply { remove(BuildingType.TOWN_HALL) }
    val buildings = types.mapIndexed { index, type ->
        val point = CivPosition(-48 + index % 8 * 12, 64, -24 + index / 8 * 12)
        CivBuilding(UUID(139, index + 1L), type, type.maxLevel, point, 0,
            if (type == BuildingType.PLAN_BOARD) CivsPlanBoardBlueprint.SCHEMATIC_ID else "fixture/$type",
            CivBox(point.copy(x = point.x - 2, y = 63, z = point.z - 3), point.copy(x = point.x + 2, y = 69, z = point.z - 1)),
            point, BuildingPhase.ACTIVE, point.copy(z = point.z - 1))
    }
    return CivsRules.validate(Settlement(UUID(139, 400), UUID(139, 401), "Staffing", UUID(139, 402),
        CivPosition(0, 64, 0), 128, "fixture", Stock(), buildings, emptyList(),
        autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
}
