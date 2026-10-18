package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsDevelopmentRulesTest : FunSpec({
    test("incremental orders can start empty and pay a row exactly once across wait pause and reload") {
        var city = builderCity().let { it.copy(inventory = it.inventory!!.copy(materials = emptyMap())) }
        val hut = city.building(id(2))!!
        val order = batches(hut, 2).copy(batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 3L),
            payment = CivConstructionPayment())
        city = CivsRules.startSite(city, city.owner, hut, order)
        city.inventory!!.materials shouldBe emptyMap()
        city = trip(city)
        denied("materials-required") { CivsRules.beginPlacement(city, city.owner, order.id) }
        city = roundTrip(CivsRules.awaitMaterials(city, city.owner, order.id, 0, mapOf("STONE" to 2L)))
        city.construction!!.phase shouldBe ConstructionPhase.WAITING_MATERIALS
        city.construction!!.materials shouldBe mapOf("STONE" to 3L)
        denied("stale-construction") { CivsRules.payBatch(city, city.owner, order.id, 1, mapOf("STONE" to 2L)) }
        city = CivsRules.payBatch(city, city.owner, order.id, 0, mapOf("STONE" to 2L))
        city.construction!!.materials shouldBe mapOf("STONE" to 1L)
        denied("stale-construction") { CivsRules.payBatch(city, city.owner, order.id, 0, mapOf("STONE" to 2L)) }
        city = roundTrip(CivsRules.pauseConstruction(city, city.owner, order.id, "placement-cancelled"))
        city = CivsRules.retryConstruction(city, city.owner, order.id)
        city.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
        city.construction!!.payment!!.reservedBatch shouldBe 0
        city = CivsRules.completeBatch(CivsRules.beginPlacement(city, city.owner, order.id), city.owner, order.id)
        city.construction!!.payment shouldBe CivConstructionPayment()
        city = trip(city)
        city = CivsRules.payBatch(city, city.owner, order.id, 1, mapOf("STONE" to 1L))
        city = CivsRules.completeBatch(roundTrip(city), city.owner, order.id)
        city.construction shouldBe null
        city.building(hut.id)!!.phase shouldBe BuildingPhase.ACTIVE
    }

    test("incremental payment and control receipts validate their persisted identity") {
        val city = builderCity()
        val hut = city.building(id(2))!!
        val order = batches(hut, 2).copy(batches = CivConstructionBatches(2, 16), payment = CivConstructionPayment())
        val working = CivsRules.startSite(city, city.owner, hut, order)
        denied("invalid-state") { CivsRules.validate(working.copy(construction = working.construction!!.copy(phase = ConstructionPhase.PLACING))) }
        denied("invalid-state") { CivsRules.validate(working.copy(construction = working.construction!!.copy(
            phase = ConstructionPhase.WAITING_MATERIALS, payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L))))) }
        val grant = CivControlPlacement(hut.id, hut.control!!, "minecraft:air")
        roundTrip(city.copy(pendingControls = listOf(grant))).pendingControls shouldBe listOf(grant)
        denied("invalid-state") { CivsRules.validate(city.copy(pendingControls = listOf(grant.copy(position = requireNotNull(hut.control).copy(x = 100))))) }
        denied("invalid-state") { CivsRules.validate(city.copy(pendingControls = listOf(grant, grant))) }
        val json = Gson().toJsonTree(working).asJsonObject
        json.getAsJsonObject("construction").remove("payment")
        val prepaid = CivsRules.validate(Gson().fromJson(json, Settlement::class.java))
        prepaid.construction!!.payment shouldBe null
        CivsRules.beginPlacement(trip(prepaid), city.owner, order.id).construction!!.phase shouldBe ConstructionPhase.PLACING
    }

    test("founding reserves a town hall site and a single item grant without a construction or residents") {
        val city = newCity()
        city.buildings.single().phase shouldBe BuildingPhase.SITE
        city.construction shouldBe null
        city.workers shouldBe emptyList()
        city.stock shouldBe Stock()
        city.inventory!!.materials shouldBe mapOf("OAK_LOG" to 96L, "COBBLESTONE" to 96L, "BREAD" to 24L)
        city.immigration shouldBe CivImmigration(4, 31_000)
        CivsRules.slots(city.buildings.single()) shouldBe 0
        CivsRules.isDepot(city.buildings.single()) shouldBe true
        CivsRules.beds(city) shouldBe 4
        CivsRules.validate(roundTrip(city)) shouldBe city
    }

    test("pioneers arrive one at a time without catch up or a second grant after reload") {
        var city = newCity()
        denied("immigration-not-ready") { CivsRules.immigrate(city, city.owner, id(100), "Первый", 30_999) }
        city = CivsRules.immigrate(city, city.owner, id(100), "Первый", 31_000)
        city.workers.size shouldBe 1
        city.immigration shouldBe CivImmigration(3, 91_000)
        city = roundTrip(city)
        denied("immigration-not-ready") { CivsRules.immigrate(city, city.owner, id(101), "Второй", 31_000) }
        city = CivsRules.immigrate(city, city.owner, id(101), "Второй", 9_000_000)
        city.workers.size shouldBe 2
        city.immigration shouldBe CivImmigration(2, 9_060_000)
        city = CivsRules.immigrate(city, city.owner, id(102), "Третий", 9_060_000)
        city = CivsRules.immigrate(city, city.owner, id(103), "Четвёртый", 9_120_000)
        CivsRules.immigrate(roundTrip(city), city.owner, id(104), "Пятый", Long.MAX_VALUE) shouldBe city
        city.inventory shouldBe newCity().inventory
        city.stock shouldBe Stock()
        city.workers.all { it.assignment == null && it.completedCycles == 0L } shouldBe true
    }

    test("builder site bootstraps itself before the hall and each real trip authorizes only one batch") {
        var city = builderCity()
        val hut = city.building(id(2))!!
        val order = batches(hut, 2)
        city = CivsRules.startSite(city, city.owner, hut, order)
        city.building(id(1))!!.phase shouldBe BuildingPhase.SITE
        city.building(hut.id)!!.phase shouldBe BuildingPhase.CONSTRUCTING
        CivsRules.startSite(city, city.owner, hut, order) shouldBe city
        city = trip(city)
        city.construction!!.workDone shouldBe 0
        city.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
        denied("construction-not-placing") { CivsRules.completeBatch(city, city.owner, order.id) }
        city = CivsRules.beginPlacement(city, city.owner, order.id)
        denied("invalid-construction") { CivsRules.completeConstruction(city, city.owner, order.id) }
        city = CivsRules.completeBatch(city, city.owner, order.id)
        city.construction!!.workDone shouldBe 1
        city.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
        denied("construction-not-placing") { CivsRules.completeBatch(city, city.owner, order.id) }
        city = CivsRules.completeBatch(CivsRules.beginPlacement(trip(roundTrip(city)), city.owner, order.id), city.owner, order.id)
        city.building(hut.id)!!.phase shouldBe BuildingPhase.ACTIVE
        city.construction shouldBe null
        city.workers.single().completedCycles shouldBe 2L
        city.inventory shouldBe newCity().inventory // Payment belongs to the service's item transaction.
        val hall = city.building(id(1))!!
        city = CivsRules.startSite(city, city.owner, hall, batches(hall, 1))
        city = finishBatch(city)
        city.building(hall.id)!!.phase shouldBe BuildingPhase.ACTIVE
        city.radius shouldBe 96
    }

    test("sites coexist with an active job but an unfinished hut cannot build a different site") {
        var city = builderCity()
        val hall = city.building(id(1))!!
        city = CivsRules.startSite(city, city.owner, hall, batches(hall, 1))
        denied("invalid-assignment") { trip(city) }
        val farm = building(3, BuildingType.FARM, 20)
        val withSite = CivsRules.placeSite(city, city.owner, farm)
        withSite.building(farm.id)!!.phase shouldBe BuildingPhase.SITE
        withSite.construction shouldBe city.construction
        withSite.stock shouldBe city.stock
        withSite.inventory shouldBe city.inventory
        denied("construction-busy") { CivsRules.startSite(withSite, withSite.owner, farm, batches(farm, 1)) }
        denied("invalid-assignment") { CivsRules.assign(withSite, withSite.owner, id(100), hall.id, 31_000) }
    }

    test("reloaded batch failure keeps paid materials and requires a fresh trip before replay") {
        var city = builderCity()
        val hut = city.building(id(2))!!
        val order = batches(hut, 2)
        city = CivsRules.beginPlacement(trip(CivsRules.startSite(city, city.owner, hut, order)), city.owner, order.id)
        city = roundTrip(CivsRules.pauseConstruction(city, city.owner, order.id, "world-changed"))
        val retried = CivsRules.retryConstruction(city, city.owner, order.id)
        retried.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
        retried.construction!!.workDone shouldBe 0
        retried.construction!!.materials shouldBe order.materials
        retried.inventory shouldBe city.inventory
        denied("construction-not-ready") { CivsRules.beginPlacement(retried, retried.owner, order.id) }
    }

    test("upgrades retain old capacity pause target production and grow territory only at final hall completion") {
        var city = builderCity()
        val hut = city.building(id(2))!!
        city = finishBatch(CivsRules.startSite(city, city.owner, hut, batches(hut, 1)))
        val hall = city.building(id(1))!!
        city = finishBatch(CivsRules.startSite(city, city.owner, hall, batches(hall, 1)))
        for (level in 2..3) {
            val upgrade = city.building(hall.id)!!.copy(level = level)
            val previousRadius = city.radius
            city = CivsRules.reserveBuild(city, city.owner, upgrade, batches(upgrade, 2))
            city.building(hall.id)!!.level shouldBe level - 1
            city = finishBatch(city)
            city.radius shouldBe previousRadius
            city = finishBatch(city)
            city.radius shouldBe previousRadius + 32
        }
        city.radius shouldBe 96 + CivsRules.MAX_TERRITORY_GROWTH
        val hutUpgrade = city.building(hut.id)!!.copy(level = 2)
        city = CivsRules.reserveBuild(city, city.owner, hutUpgrade, batches(hutUpgrade, 1))
        CivsRules.builderCanWork(city, city.building(hut.id)!!) shouldBe true
        city = finishBatch(city)
        city.building(hut.id)!!.level shouldBe 2
        val farm = building(3, BuildingType.FARM, 20, BuildingPhase.ACTIVE)
        city = CivsRules.validate(city.copy(buildings = city.buildings + farm))
        val farmUpgrade = farm.copy(level = 2)
        city = CivsRules.reserveBuild(city, city.owner, farmUpgrade, batches(farmUpgrade, 1))
        CivsRules.productionAvailable(city, farm) shouldBe false
        city.building(farm.id)!!.level shouldBe 1
    }

    test("funded recruitment and item production advance residents without duplicating abstract resources") {
        denied("town-hall-not-ready") { newCity().let { CivsRules.recruitFunded(it, it.owner, id(101), "Рано") } }
        var city = builderCity().let { it.copy(buildings = it.buildings.map { b -> b.copy(phase = BuildingPhase.ACTIVE) }) }
        city = CivsRules.validate(city)
        denied("materials-required") { CivsRules.recruit(city, city.owner, id(101), "Второй") }
        denied("duplicate-worker") { CivsRules.recruitFunded(city, city.owner, id(100), "Дубль") }
        denied("not-authorized") { CivsRules.recruitFunded(city, id(999), id(101), "Второй") }
        city = CivsRules.recruitFunded(city, city.owner, id(101), "Второй")
        city.stock shouldBe Stock()
        val farm = building(3, BuildingType.FARM, 20, BuildingPhase.ACTIVE).copy(production = "CARROT")
        city = CivsRules.validate(city.copy(buildings = city.buildings + farm))
        city = CivsRules.assign(city, city.owner, id(101), farm.id, 40_000)
        val worker = city.workers.last()
        denied("materials-required") { CivsRules.deliver(city, city.owner, worker.id, farm.id,
            worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt) }
        val delivered = CivsRules.deliver(city, city.owner, worker.id, farm.id,
            worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt, outputHandled = true)
        delivered.workers.last().completedCycles shouldBe 1L
        delivered.stock shouldBe Stock()
        delivered.inventory shouldBe city.inventory
        denied("stale-delivery") { CivsRules.deliver(delivered, city.owner, worker.id, farm.id,
            worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt, outputHandled = true) }
    }

    test("nullable additions decode old cities without replaying pioneers and malformed new progress fails closed") {
        val legacyHall = building(1, BuildingType.TOWN_HALL, 0, BuildingPhase.ACTIVE).copy(control = null)
        val legacy = Settlement(id(10), id(20), "Legacy", id(30), legacyHall.entrance, 96, "nordic", Stock(96, 96, 24),
            listOf(legacyHall), (100..103).map { CivWorker(id(it), "Житель $it", if (it == 100) legacyHall.id else null,
                nextDeliveryAt = if (it == 100) 11_000 else 0) })
        val json = Gson().toJsonTree(legacy).asJsonObject.apply { remove("inventory"); remove("immigration") }
        val old = CivsRules.validate(Gson().fromJson(json, Settlement::class.java))
        old.inventory shouldBe null
        old.immigration shouldBe null
        old.buildings.single().control shouldBe null
        old.buildings.single().production shouldBe null
        CivsRules.immigrate(old, old.owner, id(104), "Не повторять", 50_000) shouldBe old
        denied("invalid-assignment") { CivsRules.assign(old, old.owner, id(101), legacyHall.id, 50_000) }
        val freeHut = building(2, BuildingType.BUILDER_HUT, 10)
        denied("invalid-construction") { CivsRules.reserveBuild(old, old.owner, freeHut,
            batches(freeHut, 3).copy(batches = null, materials = null)) }
        val fresh = builderCity()
        val hut = fresh.building(id(2))!!
        val order = batches(hut, 2)
        listOf(order.copy(batches = CivConstructionBatches(0)), order.copy(materials = null),
            order.copy(materials = mapOf("OAK_LOG" to -1L)), order.copy(cost = Stock(1)), order.copy(workRequired = 3)).forEach {
            denied("invalid-construction") { CivsRules.startSite(fresh, fresh.owner, hut, it) }
        }
        denied("invalid-state") { CivsRules.validate(newCity().copy(immigration = CivImmigration(5, 31_000))) }
        denied("invalid-state") { CivsRules.validate(newCity().copy(immigration = CivImmigration(0, 31_000))) }
        val unfinishedUpgrade = fresh.building(id(1))!!.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)
        denied("invalid-state") { CivsRules.validate(fresh.copy(construction = batches(unfinishedUpgrade, 2))) }
        denied("invalid-state") { CivsRules.validate(newCity().copy(stock = Stock(1))) }
    }

    test("a migrated full legacy city retains one hall builder without giving new cities a hut bypass") {
        val hall = building(1, BuildingType.TOWN_HALL, 0, BuildingPhase.ACTIVE)
        var city = CivsRules.validate(newCity().copy(buildings = listOf(hall) + (2..16).map {
            building(it, BuildingType.HOUSE, (it - 1) * 5, BuildingPhase.ACTIVE)
        }, immigration = null, workers = (100..103).map { CivWorker(id(it), "Житель $it", null) }))
        CivsRules.legacyHallBuilder(city) shouldBe true
        CivsRules.slots(city, hall) shouldBe 1
        city = CivsRules.assign(city, city.owner, id(100), hall.id, 50_000)
        val upgrade = hall.copy(level = 2)
        city = CivsRules.reserveBuild(city, city.owner, upgrade, batches(upgrade, 1))
        city = finishBatch(roundTrip(city))
        city.building(hall.id)!!.level shouldBe 2
        city.buildings.size shouldBe 16
        CivsRules.legacyHallBuilder(city.copy(buildings = city.buildings.dropLast(1))) shouldBe false
        CivsRules.slots(newCity(), newCity().buildings.single()) shouldBe 0
        val withHut = city.copy(buildings = city.buildings.dropLast(1) + building(16, BuildingType.BUILDER_HUT, 75, BuildingPhase.ACTIVE))
        CivsRules.legacyHallBuilder(withHut) shouldBe false
    }
})

private fun id(value: Int) = UUID(1, value.toLong())
private fun building(key: Int, type: BuildingType, x: Int, phase: BuildingPhase = BuildingPhase.SITE) = CivBuilding(
    id(key), type, 1, CivPosition(x, 64, 2), 0, "test/${type.name.lowercase()}1",
    CivBox(CivPosition(x - 1, 64, -1), CivPosition(x + 1, 68, 1)), CivPosition(x, 65, 2), phase,
    control = CivPosition(x, 64, 2))
private fun newCity(): Settlement {
    val hall = building(1, BuildingType.TOWN_HALL, 0)
    return CivsRules.found(id(10), id(20), "Город", id(30), hall.entrance, 96, "nordic", hall, 1_000)
}
private fun builderCity(): Settlement {
    var city = newCity()
    city = CivsRules.immigrate(city, city.owner, id(100), "Строитель", 31_000)
    city = CivsRules.placeSite(city, city.owner, building(2, BuildingType.BUILDER_HUT, 10))
    return CivsRules.assign(city, city.owner, id(100), id(2), 31_000)
}
private fun batches(building: CivBuilding, count: Int) = CivConstruction(
    id(1_000 + building.id.leastSignificantBits.toInt() + building.level * 100), building.id, building.level,
    count, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building.copy(phase = BuildingPhase.CONSTRUCTING),
    batches = CivConstructionBatches(count), materials = mapOf("OAK_LOG" to 1L))
private fun trip(city: Settlement): Settlement {
    val worker = city.workers.first()
    return CivsRules.deliver(city, city.owner, worker.id, requireNotNull(worker.assignment),
        worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt)
}
private fun finishBatch(city: Settlement): Settlement {
    val order = city.construction!!
    val placing = CivsRules.beginPlacement(trip(city), city.owner, order.id)
    return CivsRules.completeBatch(placing, city.owner, order.id)
}
private fun roundTrip(city: Settlement): Settlement = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
private fun denied(reason: String, action: () -> Unit) { shouldThrow<CivsRejection>(action).reason shouldBe reason }
