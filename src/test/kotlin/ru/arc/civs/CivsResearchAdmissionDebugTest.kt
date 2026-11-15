package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsResearchAdmissionDebugTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("an eligible offer reports the actual free material deficit without refreshing supply on debug") {
        val h = ResearchAdmissionFixture()
        val warehouse = h.city.buildings.single { it.type == BuildingType.WAREHOUSE }
        h.snapshot = supplyObservation(h.city, mapOf(warehouse.id to mapOf("COBBLESTONE" to 63L, "BREAD" to 8L)))
        CivsResearch.view(h.city).offers.single { it.research == CivResearchId.BUILDING_METHODS_I }.available shouldBe true
        h.research.pulseResearch()
        h.time += 250
        val debug = h.research.admissionDebug(h.city)
        debug["source"] shouldBe "last-research-pulse"
        debug["freshNativeRead"] shouldBe false
        val decision = debug["decision"] as Map<*, *>
        decision["reason"] shouldBe "materials-unavailable"
        decision["selected"] shouldBe null
        decision["availableMaterials"] shouldBe mapOf("COBBLESTONE" to 63L, "BREAD" to 8L)
        (decision["missingMaterials"] as Map<*, *>)["BUILDING_METHODS_I"] shouldBe mapOf("COBBLESTONE" to 1L)
        decision["ageMillis"] shouldBe 250L
        decision["revisionChanged"] shouldBe false
        h.research.admissionDebug(h.city) shouldBe debug
        h.supplyReads shouldBe 1
        verify(exactly = 1) { h.storage.supplyCaptureCurrent(any(), any(), any(), any()) }
        verify(exactly = 0) { h.authority.mutate(any(), any(), any()) }
    }

    test("missing supply changed inputs and stale capture retain their distinct actual admission gates") {
        val h = ResearchAdmissionFixture()
        h.snapshot = null
        h.research.pulseResearch()
        h.decision()["reason"] shouldBe "supply-observation-missing"
        val changed = h.city.copy(workers = h.city.workers.map { it.copy(completedCycles = it.completedCycles + 1) })
        h.snapshot = supplyObservation(changed)
        h.research.pulseResearch()
        h.decision()["reason"] shouldBe "supply-observation-not-current"
        verify(exactly = 0) { h.storage.supplyCaptureCurrent(any(), any(), any(), any()) }
        h.snapshot = supplyObservation(h.city)
        h.captureCurrent = false
        h.research.pulseResearch()
        h.decision()["reason"] shouldBe "supply-capture-not-current"
        h.decision()["availableMaterials"] shouldBe null
        verify(exactly = 1) { h.storage.supplyCaptureCurrent(any(), any(), any(), any()) }
        verify(exactly = 0) { h.authority.mutate(any(), any(), any()) }
    }

    test("a busy city exposes the last decision age and revision then deletion removes its observations") {
        val h = ResearchAdmissionFixture()
        h.research.pulseResearch()
        val changed = h.city.copy(revision = h.city.revision + 1)
        h.cities[h.city.id] = changed
        h.busy += h.city.id
        h.time += 1_000
        h.research.pulseResearch()
        val debug = h.research.admissionDebug(changed)
        (debug["eligibility"] as Map<*, *>)["reason"] shouldBe "city-busy"
        (debug["decision"] as Map<*, *>)["ageMillis"] shouldBe 1_000L
        (debug["decision"] as Map<*, *>)["revisionChanged"] shouldBe true
        h.supplyReads shouldBe 1
        h.cities.clear()
        h.research.pulseResearch()
        h.research.admissionDebug(changed)["eligibility"] shouldBe null
        h.research.admissionDebug(changed)["decision"] shouldBe null
    }
})

private class ResearchAdmissionFixture {
    private val base = physicalAutoFixture()
    private val warehouse = base.buildings.single { it.type == BuildingType.WAREHOUSE }
    private val university = base.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.UNIVERSITY,
        anchor = CivPosition(80, 64, 0), control = CivPosition(80, 64, 0), entrance = CivPosition(80, 64, -3),
        bounds = CivBox(CivPosition(78, 62, -2), CivPosition(82, 68, 2)))
    val city = CivsRules.validate(base.copy(buildings = base.buildings + university, workers = listOf(
        CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1_000),
        CivWorker(UUID.randomUUID(), "Researcher", university.id, nextDeliveryAt = 1_000)),
        research = CivResearchState(CivResearchId.entries.take(4).toSet())))
    val cities = linkedMapOf(city.id to city)
    val busy = mutableSetOf<UUID>()
    val authority = mockk<CivsService>()
    val storage = mockk<CivsStorage>()
    var time = 10_000L
    var captureCurrent = true
    var snapshot: CivSupplySnapshot? = supplyObservation(city)
    var supplyReads = 0
    val research = CivsCityResearch(authority,
        CivsCityRecords({ cities }, busy, emptySet(), { false }, { false }, { null }, { 0L }, { 0 }),
        mockk<LifecycleTaskScope>(), storage, researchSupply = { supplyReads++; snapshot })

    init {
        every { authority.now() } answers { time }
        every { authority.settlementActive(any()) } returns true
        every { storage.supplyCaptureCurrent(any(), any(), any(), any()) } answers { captureCurrent }
    }

    fun decision() = research.admissionDebug(city)["decision"] as Map<*, *>
}
