package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsTradeOffersTest : FunSpec({
    test("bids stop at the real shortage and an empty treasury cannot buy goods") {
        val city = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = null)
        val hall = city.buildings.single()
        fun snapshot(cobble: Long) = CivSupplySnapshot(city.id, Unit, mapOf(hall.id to
            CivSupplyStore(hall.id, hall.entrance, hall.control ?: hall.entrance,
                mapOf("COBBLESTONE" to cobble, "BREAD" to 32L), mapOf("COBBLESTONE" to 128L, "BREAD" to 16L),
                null, CivSupplyCapacity(20, emptyMap()), BuildingType.TOWN_HALL)),
            emptyList(), emptyList(), emptyList(), emptyMap(), mapOf("COBBLESTONE" to 64))
        val cash = mapOf("EMERALD" to 1L, "BREAD" to 32L)
        val bid = CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64)
        (bid in CivsTrade.offers(city, snapshot(0), cash)) shouldBe true
        (bid in CivsTrade.offers(city, snapshot(64), cash)) shouldBe true
        (bid in CivsTrade.offers(city, snapshot(65), cash)) shouldBe false
        (bid in CivsTrade.offers(city, snapshot(0), cash - "EMERALD")) shouldBe false
        (CivTradeOffer("BREAD", CivTradeDirection.BUY, 8) in CivsTrade.offers(city, snapshot(0), cash)) shouldBe true
        (CivTradeOffer("BREAD", CivTradeDirection.SELL, 16) in CivsTrade.offers(city, snapshot(0), cash)) shouldBe false
    }

    test("Auto commerce reserves one bid batch beyond proven demand for both buying and selling") {
        val city = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        val hall = city.buildings.single()
        val bid = CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64)
        val ask = CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32)
        fun offers(count: Long, emeralds: Long = 1L) = CivsTrade.offers(city,
            tradeOfferSnapshot(city, mapOf(hall.id to mapOf("COBBLESTONE" to count)),
                mapOf(hall.id to mapOf("COBBLESTONE" to 16L))),
            mapOf("COBBLESTONE" to count, "EMERALD" to emeralds))
        offers(16) shouldBe listOf(bid)
        offers(16, emeralds = 0) shouldBe emptyList()
        val filled = 16L + bid.amount
        offers(filled) shouldBe emptyList()
        offers(filled - 1) shouldBe emptyList()
        offers(filled + ask.amount - 1) shouldBe emptyList()
        val surplus = filled + ask.amount
        offers(surplus) shouldBe listOf(ask)
        // Buying the visible surplus leaves the same reserve; it cannot open a new bid.
        offers(surplus - ask.amount) shouldBe emptyList()
    }

    test("Auto without demand and manual cities do not acquire the commerce buffer") {
        val auto = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        val hall = auto.buildings.single()
        CivsTrade.offers(auto, tradeOfferSnapshot(auto, emptyMap(), emptyMap()), mapOf("EMERALD" to 1L)) shouldBe emptyList()
        val manual = auto.copy(autonomy = null)
        val snapshot = tradeOfferSnapshot(manual, mapOf(hall.id to mapOf("COBBLESTONE" to 48L)),
            mapOf(hall.id to mapOf("COBBLESTONE" to 16L)))
        CivsTrade.offers(manual, snapshot, mapOf("COBBLESTONE" to 48L, "EMERALD" to 1L)) shouldBe
            listOf(CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32))
    }

    test("a paid unknown bill does not turn its observed-stock protection into new Auto demand") {
        val base = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        val hall = base.buildings.single()
        val city = base.copy(construction = CivConstruction(UUID(94, 450), hall.id, 2, 1, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), materials = null, payment = CivConstructionPayment()))
        val snapshot = tradeOfferSnapshot(city, mapOf(hall.id to mapOf("COBBLESTONE" to 16L)),
            mapOf(hall.id to mapOf("COBBLESTONE" to 16L)))
        CivsTrade.offers(city, snapshot, mapOf("COBBLESTONE" to 16L, "EMERALD" to 1L)) shouldBe emptyList()
    }

    test("a paid courier batch counts once inside its actual bag and cannot create saleable surplus") {
        val base = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        val hall = base.buildings.single()
        val warehouse = hall.copy(id = UUID(94, 200), type = BuildingType.WAREHOUSE, control = CivPosition(8, 64, 0))
        val workerId = UUID(94, 201)
        val cargo = CivCargo(CivsLogistics.shipmentId(base.id, workerId, 0, hall.id, warehouse.id, "COBBLESTONE"),
            warehouse.id, hall.id, warehouse.id, hall.entrance, "COBBLESTONE", 64, inventoryBacked = true)
        val worker = CivWorker(workerId, "Courier", warehouse.id, cargo = cargo,
            items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 64) })
        val city = base.copy(buildings = listOf(hall, warehouse), workers = listOf(worker))
        val snapshot = tradeOfferSnapshot(city, mapOf(hall.id to mapOf("COBBLESTONE" to 64L)),
            mapOf(hall.id to mapOf("COBBLESTONE" to 128L)))
        val offers = CivsTrade.offers(city, snapshot, mapOf("COBBLESTONE" to 64L, "EMERALD" to 1L))
        (CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32) in offers) shouldBe false
        (CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64) in offers) shouldBe true
    }

    for (huts in listOf(1, 2))
    test("a shared paid construction bill is reserved once across $huts builder buffers") {
        val base = settlementPlanCity().copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        val hall = base.buildings.single()
        val builders = List(huts) { index -> hall.copy(id = UUID(94, 300L + index), type = BuildingType.BUILDER_HUT,
            control = CivPosition(8 + index * 8, 64, 0)) }
        val order = CivConstruction(UUID(94, 400), hall.id, 2, 1, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), materials = mapOf("COBBLESTONE" to 64L), payment = CivConstructionPayment())
        val city = base.copy(buildings = listOf(hall) + builders, construction = order)
        val reserves = builders.associate { it.id to mapOf("COBBLESTONE" to 64L) }
        val bid = CivTradeOffer("COBBLESTONE", CivTradeDirection.SELL, 64)
        val ask = CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32)
        val complete = tradeOfferSnapshot(city, mapOf(hall.id to mapOf("COBBLESTONE" to 128L)), reserves)
        val stock = mapOf("COBBLESTONE" to 128L, "EMERALD" to 1L)
        (bid in CivsTrade.offers(city, complete, stock)) shouldBe false
        (ask in CivsTrade.offers(city, complete, stock)) shouldBe false
        val missing = tradeOfferSnapshot(city, emptyMap(), reserves)
        (bid in CivsTrade.offers(city, missing, stock - "COBBLESTONE")) shouldBe true
    }
})

private fun tradeOfferSnapshot(city: Settlement, stock: Map<UUID, Map<String, Long>>,
    desired: Map<UUID, Map<String, Long>>): CivSupplySnapshot = CivSupplySnapshot(city.id, Unit,
    city.buildings.associate { building -> building.id to CivSupplyStore(building.id, building.entrance,
        building.control ?: building.entrance, stock[building.id].orEmpty(), desired[building.id].orEmpty(),
        null, CivSupplyCapacity(20, emptyMap()), building.type) },
    emptyList(), emptyList(), emptyList(), emptyMap(), mapOf("COBBLESTONE" to 64))
