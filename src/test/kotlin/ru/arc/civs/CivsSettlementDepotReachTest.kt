package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSettlementDepotReachTest : FunSpec({
    test("an unpaid warehouse cannot authorize a remote site before its native building becomes active") {
        val city = settlementPlanCity().copy(radius = 128)
        val warehouse = CivBuilding(UUID.randomUUID(), BuildingType.WAREHOUSE, 1, CivPosition(70, 64, 0), 0,
            "fixture/warehouse", CivBox(CivPosition(68, 64, -5), CivPosition(72, 68, -1)),
            CivPosition(70, 65, 0), BuildingPhase.SITE)
        val house = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, CivPosition(110, 64, 0), 0,
            "fixture/house", CivBox(CivPosition(108, 64, -5), CivPosition(112, 68, -1)),
            CivPosition(110, 65, 0), BuildingPhase.SITE)
        val unpaid = city.copy(buildings = city.buildings + warehouse)
        shouldThrow<CivsRejection> { CivsRules.placeSite(unpaid, city.owner, house) }.reason shouldBe "work-route-too-long"
        val accepted = city.copy(buildings = city.buildings + warehouse.copy(phase = BuildingPhase.ACTIVE))
        CivsRules.placeSite(accepted, city.owner, house).building(house.id) shouldBe house
    }
})
