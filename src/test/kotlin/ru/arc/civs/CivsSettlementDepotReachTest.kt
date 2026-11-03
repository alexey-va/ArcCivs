package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSettlementDepotReachTest : FunSpec({
    val auto by lazy {
        val city = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        CivsRules.validate(city.copy(workers = listOf(CivWorker(UUID(204, 1), "Builder", null,
            items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 17) else null }))))
    }

    test("Auto infrastructure admits the same diagonal site and native bill through both construction gates") {
        for (type in listOf(BuildingType.ROAD, BuildingType.WALL, BuildingType.GATE)) withClue(type) {
            val building = depotReachSite(type, CivPosition(70, 65, 70))
            val order = depotReachOrder(building)
            CivsSnapshotValidation.validConstructionPrice(auto, order, requireNotNull(order.plannedBuilding)) shouldBe true
            val site = CivsRules.placeSite(auto, auto.owner, building)
            val reserved = CivsRules.reserveBuild(site, site.owner, building, order)
            reserved.construction shouldBe order
            reserved.building(building.id)?.bounds shouldBe building.bounds
            reserved.buildings.take(auto.buildings.size) shouldBe auto.buildings
            reserved.stock shouldBe auto.stock
            reserved.inventory shouldBe auto.inventory
            reserved.workers shouldBe auto.workers
            CivsRules.reserveBuild(reserved, reserved.owner, building, order) shouldBe reserved
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(site, site.owner, building, order.copy(payment = null)) }
                .reason shouldBe "invalid-construction"
        }
    }

    test("manual infrastructure retains eighty eight blocks at both construction gates") {
        val manual = auto.copy(autonomy = null)
        for (type in listOf(BuildingType.ROAD, BuildingType.WALL, BuildingType.GATE)) withClue(type) {
            val building = depotReachSite(type, CivPosition(70, 65, 70))
            val order = depotReachOrder(building)
            CivsSnapshotValidation.validConstructionPrice(manual, order, requireNotNull(order.plannedBuilding)) shouldBe true
            shouldThrow<CivsRejection> { CivsRules.placeSite(manual, manual.owner, building) }.reason shouldBe "work-route-too-long"
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(manual, manual.owner, building, order) }.reason shouldBe "work-route-too-long"
        }
    }

    test("an ordinary Auto house still uses eighty eight blocks for site and construction admission") {
        val house = depotReachSite(BuildingType.HOUSE, CivPosition(70, 65, 70))
        shouldThrow<CivsRejection> { CivsRules.placeSite(auto, auto.owner, house) }.reason shouldBe "work-route-too-long"
        shouldThrow<CivsRejection> { CivsRules.reserveBuild(auto, auto.owner, house, depotReachOrder(house)) }
            .reason shouldBe "work-route-too-long"
    }

    test("infrastructure reach includes height and enforces the exact city limit at both admissions") {
        val city = auto.copy(radius = 65)
        CivsLogistics.maximumTransferDistance(city) shouldBe 130
        // 30 squared + 120 squared + 40 squared = 130 squared from the hall entrance.
        val boundary = depotReachSite(BuildingType.ROAD, CivPosition(30, 185, 40))
        CivsRules.workRouteWithinReach(city, boundary.entrance) shouldBe false
        val site = CivsRules.placeSite(city, city.owner, boundary)
        CivsRules.reserveBuild(site, site.owner, boundary, depotReachOrder(boundary)).construction?.buildingId shouldBe boundary.id
        val above = depotReachSite(BuildingType.ROAD, boundary.entrance.copy(y = boundary.entrance.y + 1))
        shouldThrow<CivsRejection> { CivsRules.placeSite(city, city.owner, above) }.reason shouldBe "work-route-too-long"
        shouldThrow<CivsRejection> { CivsRules.reserveBuild(city, city.owner, above, depotReachOrder(above)) }.reason shouldBe "work-route-too-long"
    }

    test("the nearest active warehouse counts while a missing or unpaid nearby warehouse does not") {
        val hall = auto.buildings.single()
        fun moved(p: CivPosition) = p.copy(x = p.x - 80, z = p.z - 80)
        val city = auto.copy(buildings = listOf(hall.copy(anchor = moved(hall.anchor), entrance = moved(hall.entrance),
            bounds = CivBox(moved(hall.bounds.min), moved(hall.bounds.max)))))
        val warehouse = depotReachSite(BuildingType.WAREHOUSE, CivPosition(50, 65, 50))
        val building = depotReachSite(BuildingType.GATE, CivPosition(80, 65, 80))
        for (observed in listOf(city, city.copy(buildings = city.buildings + warehouse))) {
            shouldThrow<CivsRejection> { CivsRules.placeSite(observed, observed.owner, building) }.reason shouldBe "work-route-too-long"
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(observed, observed.owner, building, depotReachOrder(building)) }
                .reason shouldBe "work-route-too-long"
        }
        val active = city.copy(buildings = city.buildings + warehouse.copy(phase = BuildingPhase.ACTIVE))
        val site = CivsRules.placeSite(active, active.owner, building)
        CivsRules.reserveBuild(site, site.owner, building, depotReachOrder(building)).construction?.buildingId shouldBe building.id
    }

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

private fun depotReachSite(type: BuildingType, entrance: CivPosition): CivBuilding {
    val bounds = CivBox(entrance.copy(x = entrance.x - 2, y = entrance.y - 1, z = entrance.z - 3),
        entrance.copy(x = entrance.x + 2, y = entrance.y + 3, z = entrance.z - 1))
    val transit = if (type in setOf(BuildingType.ROAD, BuildingType.GATE)) buildList<CivPosition> {
        for (x in bounds.min.x..bounds.max.x) for (z in bounds.min.z..bounds.max.z) add(CivPosition(x, entrance.y, z))
    } else null
    return CivBuilding(UUID.randomUUID(), type, 1, entrance.copy(y = entrance.y - 1), 0, "fixture/${type.name.lowercase()}",
        bounds, entrance, BuildingPhase.SITE, transit = transit)
}

private fun depotReachOrder(building: CivBuilding) = CivConstruction(UUID.randomUUID(), building.id, 1, 2, 0,
    ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building.copy(phase = BuildingPhase.CONSTRUCTING),
    batches = CivConstructionBatches(2, 16, atomicPairs = true), materials = mapOf("STONE_BRICKS" to 2L),
    payment = CivConstructionPayment(), blocksRequired = 2, blocksPlaced = 0)
