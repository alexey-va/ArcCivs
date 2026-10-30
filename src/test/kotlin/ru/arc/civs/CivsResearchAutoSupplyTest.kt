package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsResearchAutoSupplyTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("new project bills remain courier requests and never become automatic or selected-input grants") {
        val city = CivsResearch.begin(researchSupplyCity(), CivResearchId.BUILDING_METHODS_I, UUID.randomUUID(), 1_000)
        val university = requireNotNull(CivsResearch.university(city))
        val worker = city.workers.single { it.assignment == university.id }
        val bill = requireNotNull(city.research?.project).cost.materials
        val observation = supplyObservation(city)
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observation,
            selectedInputs = mapOf(worker.id to bill)).none { it.buildingId == university.id } shouldBe true
        val requests = CivsLogistics.requests(city) { emptyMap() }.filter { it.buildingId == university.id }
        requests.associate { it.material to it.needed } shouldBe bill
        requests.all { !it.construction } shouldBe true
    }

    test("historical project supply policy is unchanged") {
        val plain = researchSupplyCity().copy(research = CivResearchState())
        val city = CivsResearch.begin(plain, CivResearchId.MATERIALS, UUID.randomUUID(), 1_000)
        val university = requireNotNull(CivsResearch.university(city))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city))
            .single { it.buildingId == university.id }.required shouldBe requireNotNull(city.research?.project).cost.materials
    }

    test("optional auto research needs real reachable stock and a staffed relay") {
        val city = researchSupplyCity()
        val warehouse = city.buildings.single { it.type == BuildingType.WAREHOUSE }
        val bill = CivsResearch.cost(city, CivResearchId.BUILDING_METHODS_I).materials
        val snapshot = supplyObservation(city, mapOf(warehouse.id to bill))
        CivsAutoSupplies.observed(city, snapshot) shouldBe true
        CivsResearch.automatic(city) shouldBe null
        CivsResearch.automatic(city, civsResearchAvailableMaterials(city, snapshot)) shouldBe CivResearchId.BUILDING_METHODS_I
        val unstaffed = city.copy(workers = city.workers.filterNot { it.assignment == warehouse.id })
        CivsResearch.automatic(unstaffed, civsResearchAvailableMaterials(unstaffed,
            supplyObservation(unstaffed, mapOf(warehouse.id to bill)))) shouldBe null
        val changed = city.copy(workers = city.workers.map { it.copy(completedCycles = it.completedCycles + 1) })
        CivsAutoSupplies.observed(changed, snapshot) shouldBe false
    }

    test("construction requests and stored targets retain priority over optional project admission") {
        val city = researchSupplyCity()
        val warehouse = city.buildings.single { it.type == BuildingType.WAREHOUSE }
        val bill = CivsResearch.cost(city, CivResearchId.BUILDING_METHODS_I).materials
        val snapshot = supplyObservation(city, mapOf(warehouse.id to bill))
        val construction = snapshot.copy(requests = listOf(CivSupplyRequest(warehouse.id, "COBBLESTONE", 1, 0, true)))
        CivsResearch.automatic(city, civsResearchAvailableMaterials(city, construction)) shouldBe null
        val target = snapshot.copy(stockTargets = mapOf(warehouse.id to mapOf("BREAD" to 1L)))
        CivsResearch.automatic(city, civsResearchAvailableMaterials(city, target)) shouldBe null
        val worker = city.workers.first()
        val job = CivCraftJob(UUID.randomUUID(), worker.id, warehouse.id, worker.completedCycles, "test/reserved", 1,
            "STONE", 1, mapOf("COBBLESTONE" to 1L), "STONE", warehouse.id)
        val reserved = snapshot.copy(craftJobs = mapOf(worker.id to job))
        CivsResearch.automatic(city, civsResearchAvailableMaterials(city, reserved)) shouldBe null
    }
})

private fun researchSupplyCity(): Settlement {
    val base = physicalAutoFixture()
    val warehouse = base.buildings.single { it.type == BuildingType.WAREHOUSE }
    val university = base.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.UNIVERSITY,
        anchor = CivPosition(80, 64, 0), control = CivPosition(80, 64, 0), entrance = CivPosition(80, 64, -3),
        bounds = CivBox(CivPosition(78, 62, -2), CivPosition(82, 68, 2)))
    return CivsRules.validate(base.copy(buildings = base.buildings + university, workers = listOf(
        CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1_000),
        CivWorker(UUID.randomUUID(), "Researcher", university.id, nextDeliveryAt = 1_000)),
        research = CivResearchState(CivResearchId.entries.take(4).toSet())))
}
