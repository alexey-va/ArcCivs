package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsProcessorDirectInputTest : FunSpec({
    val routes = listOf(
        Triple("GLASS", BuildingType.SMELTERY, BuildingType.GUARD_TOWER),
        Triple("IRON_INGOT", BuildingType.SMELTERY, BuildingType.BLACKSMITH),
        Triple("SAND", BuildingType.CRUSHER, BuildingType.SMELTERY),
    )
    for ((material, producer, consumer) in routes) {
        test("a full depot can send physically stocked $material from an automatic recipe") {
            MockBukkitTestRuntime.open().use {
                val hall = processorBuilding(0, BuildingType.TOWN_HALL)
                val source = processorBuilding(8, producer)
                val target = processorBuilding(16, consumer).copy(production = "GLASS".takeIf { consumer == BuildingType.SMELTERY })
                val depot = processorBuilding(24, BuildingType.WAREHOUSE)
                val workers = listOf(source, target, depot).map { CivWorker(UUID(0, 100 + it.id.leastSignificantBits),
                    it.type.name, it.id) }
                val city = Settlement(UUID(0, 200), UUID(0, 201), "Village", UUID(0, 202), hall.anchor, 80,
                    "nordic", Stock(), listOf(hall, source, target, depot), workers,
                    autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL),
                    settings = CivSettlementSettings(garrisonSupplies = true),
                    research = CivResearchState(setOf(CivResearchId.MATERIALS, CivResearchId.METALLURGY, CivResearchId.GLASSWORK)))
                val snapshot = processorSupply(city, source, depot, material, depotRoom = 0)
                snapshot.stores.getValue(source.id).outputMaterial shouldBe CivsProduction.material(source)
                (material in snapshot.stores.getValue(source.id).alternateOutputs) shouldBe true
                val request = snapshot.requests.single { it.buildingId == target.id && it.material == material }
                val trips = allocateCivSupplies(snapshot).plans
                val cargo = trips.values.single().cargo
                cargo.directInput shouldBe true
                cargo.sourceBuildingId shouldBe source.id
                cargo.targetBuildingId shouldBe target.id
                cargo.amount shouldBe request.needed
                CivsLogistics.directInputAllowed(city, cargo) shouldBe true
                allocateCivSupplies(snapshot.copy(retained = trips)).plans shouldBe trips
                CivsLogistics.directInputAllowed(city.copy(autonomy = null), cargo) shouldBe false
                CivsLogistics.directInputAllowed(city.copy(research = null), cargo) shouldBe false
                val fixed = source.copy(production = CivsProduction.material(source))
                CivsLogistics.directInputAllowed(city.copy(buildings = city.buildings.map { if (it.id == source.id) fixed else it }),
                    cargo) shouldBe false

                val roomy = processorSupply(city, source, depot, material, depotRoom = 3)
                val relay = allocateCivSupplies(roomy).plans.values.single().cargo
                relay.directInput shouldBe false
                relay.sourceBuildingId shouldBe source.id
                relay.targetBuildingId shouldBe depot.id
            }
        }
    }
})

private fun processorBuilding(x: Int, type: BuildingType) = CivBuilding(UUID(0, x.toLong()), type, 3,
    CivPosition(x, 64, 0), 0, "test/$type", CivBox(CivPosition(x - 2, 63, -2), CivPosition(x + 2, 69, 2)),
    CivPosition(x, 64, -3), BuildingPhase.ACTIVE, CivPosition(x, 64, 0))

private fun processorSupply(city: Settlement, source: CivBuilding, depot: CivBuilding, material: String,
    depotRoom: Int): CivSupplySnapshot {
    val capture = mockk<CivsSupplyCapture>()
    every { capture.step() } returns true
    every { capture.result } returns CivSupplyCaptureResult(city.id, city.buildings.associate { building ->
        val control = requireNotNull(building.control)
        building.id to CivSupplyCapturedStore(if (building == source) mapOf(material to 16L) else emptyMap(),
            CivSupplyCapacity(if (building == depot) depotRoom else 27, emptyMap()),
            CivSupplyStorageKey(city.id, city.worldId, city.owner, CivStorageMode.PHYSICAL,
                building.id, building.type, building.phase, building.level, building.bounds, control,
                mapOf(control to "BARREL"), emptyMap(), 0))
    })
    val planner = CivsSupplyPlanning(city, capture, emptyMap(), { null }, { false })
    repeat(city.workers.size + 2) { planner.step()?.let { return it.detached() } }
    error("Supply capture did not complete")
}
