package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsWorkplaceUpgradeTest : FunSpec({
    for (automatic in listOf(false, true)) for (type in listOf(BuildingType.QUARRY, BuildingType.FORESTER, BuildingType.FARM))
        test("an active $type keeps its current work during an upgrade Auto=$automatic") {
            MockBukkitTestRuntime.open().use {
                val base = physicalAutoFixture()
                val building = base.buildings.single { it.type == type }
                val worker = CivWorker(UUID.randomUUID(), "Worker", building.id, nextDeliveryAt = 1,
                    items = List(CivsEquipment.SLOTS) { slot -> if (slot == 4) CivResidentItem(
                        requireNotNull(CivsResidents.tool(type)), 1) else null })
                val city = CivsRules.validate(base.copy(autonomy = base.autonomy.takeIf { automatic }, workers = listOf(worker),
                    buildings = base.buildings.map { if (it.type == BuildingType.TOWN_HALL) it.copy(level = 2) else it },
                    construction = CivConstruction(UUID.randomUUID(), building.id, building.level + 1,
                        328, 10, ConstructionPhase.PLACING, Stock(), workCredit = 54,
                        plannedBuilding = building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING),
                        batches = CivConstructionBatches(328, 1, true), materials = mapOf("OAK_PLANKS" to 3L),
                        payment = CivConstructionPayment(reservedBatch = 10))))
                CivsRules.productionAvailable(city, building) shouldBe true
                citizenPlan(city, worker)!!.idleReason shouldBe null
                if (automatic && type == BuildingType.QUARRY) CivsMineFallback.eligible(city, building, worker) shouldBe true
                CivsRules.productionAvailable(city, building.copy(phase = BuildingPhase.CONSTRUCTING)) shouldBe false
                CivsRules.productionAvailable(city.copy(construction = city.construction!!.copy(targetLevel = building.level)), building) shouldBe false
            }
        }
})
