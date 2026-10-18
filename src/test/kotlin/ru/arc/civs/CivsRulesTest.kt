package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsRulesTest : FunSpec({
    test("automatic jobs fill the builder site first and preserve manual assignments and delivery tokens") {
        val farm = building(2, BuildingType.FARM, 8, phase = BuildingPhase.ACTIVE)
        val hut = building(3, BuildingType.BUILDER_HUT, 16, phase = BuildingPhase.SITE)
        val quarry = building(4, BuildingType.QUARRY, 24, phase = BuildingPhase.ACTIVE)
        val unavailable = building(5, BuildingType.FORESTER, 32, phase = BuildingPhase.SITE)
        val city = activeCity().let { it.copy(buildings = it.buildings + listOf(farm, hut, quarry, unavailable),
            workers = it.workers.mapIndexed { index, worker -> worker.copy(assignment = if (index == 3) farm.id else null,
                nextDeliveryAt = if (index == 3) 1_000 else worker.nextDeliveryAt) }) }
        CivsRules.needsAutoAssignment(city) shouldBe true
        val assigned = CivsRules.autoAssign(city, owner, 20_000)
        assigned.revision shouldBe city.revision + 1
        assigned.workers.map { it.assignment } shouldBe listOf(hut.id, quarry.id, null, farm.id)
        assigned.workers[3] shouldBe city.workers[3]
        assigned.workers[0].nextDeliveryAt shouldBe 20_000 + CivsRules.deliveryInterval(hut.type)
        assigned.workers[1].nextDeliveryAt shouldBe 20_000 + CivsRules.deliveryInterval(quarry.type)
        assigned.workers.map { it.completedCycles } shouldBe city.workers.map { it.completedCycles }
        assigned.stock shouldBe city.stock
        CivsRules.needsAutoAssignment(assigned) shouldBe false
        CivsRules.autoAssign(assigned, owner, 30_000) shouldBe assigned
        rejects("not-authorized") { CivsRules.autoAssign(city, uuid(999), 20_000) }
    }

    test("deletion requires current owner and exact name but permits virtual and legacy stock") {
        val city = activeCity().copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL), members = setOf(uuid(900)))
        CivsRules.requireDeletion(city, owner, city.id, " ${city.name} ") shouldBe Unit
        rejects("owner-required") { CivsRules.requireDeletion(city, uuid(900), city.id, city.name) }
        rejects("delete-city-changed") { CivsRules.requireDeletion(city, owner, uuid(901), city.name) }
        rejects("delete-name-mismatch") { CivsRules.requireDeletion(city, owner, city.id, "wrong") }
        for (stocked in listOf(city.copy(stock = Stock(food = 1), inventory = null),
            city.copy(inventory = city.inventory!!.copy(materials = mapOf("BREAD" to 1L))),
            city.copy(inventory = city.inventory!!.copy(buildingMaterials = mapOf(hallId.toString() to mapOf("BREAD" to 1L)))))) {
            CivsRules.requireDeletion(stocked, owner, city.id, city.name) shouldBe Unit
        }
    }

    test("legacy founding grant and first builder recover without another charge or worker grant") {
        val founded = foundedCity()
        founded.stock shouldBe Stock(96, 96, 24)
        founded.workers.size shouldBe 4
        founded.workers.first().assignment shouldBe hallId
        founded.building(hallId)!!.phase shouldBe BuildingPhase.CONSTRUCTING
        CivsRules.slots(founded.building(hallId)!!) shouldBe 0
        rejects("invalid-assignment") { deliver(founded, founded.workers.first()) }
        rejects("construction-not-placing") { CivsRules.completeConstruction(founded, owner, foundationId) }
        val placing = CivsRules.beginPlacement(founded, owner, foundationId)
        CivsRules.beginPlacement(placing, owner, foundationId) shouldBe placing
        val active = CivsRules.completeConstruction(placing, owner, foundationId)
        active.stock shouldBe founded.stock
        active.revision shouldBe 2L
        active.building(hallId)!!.phase shouldBe BuildingPhase.ACTIVE
        CivsRules.completeConstruction(active, owner, foundationId) shouldBe active
        CivsRules.validate(Gson().fromJson(Gson().toJson(active), Settlement::class.java)) shouldBe active
    }

    test("reserved resources are charged once and paused construction resumes the captured plan") {
        val city = activeCity()
        val warehouse = building(2, BuildingType.WAREHOUSE, x = 10)
        val order = orderFor(warehouse)
        val reserved = CivsRules.reserveBuild(city, owner, warehouse, order)
        reserved.stock shouldBe Stock(64, 80, 24)
        reserved.revision shouldBe city.revision + 1
        CivsRules.reserveBuild(reserved, owner, warehouse, order) shouldBe reserved
        val progressed = deliver(reserved, reserved.workers.first())
        progressed.construction!!.workDone shouldBe 1
        progressed.workers.first().nextDeliveryAt - reserved.workers.first().nextDeliveryAt shouldBe 10_000L
        val paused = CivsRules.pauseConstruction(progressed, owner, order.id, "route-unreachable")
        val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(paused), Settlement::class.java))
        rejects("construction-not-working") { deliver(reloaded, reloaded.workers.first()) }
        val resumed = CivsRules.retryConstruction(reloaded, owner, order.id)
        resumed.construction!!.workDone shouldBe 1
        resumed.stock shouldBe reserved.stock
        CivsRules.reserveBuild(resumed, owner, warehouse, order) shouldBe resumed
        val ready = workUntilReady(resumed)
        CivsRules.reserveBuild(ready, owner, warehouse, order) shouldBe ready
        rejects("stale-construction") { CivsRules.beginPlacement(ready, owner, uuid(9999)) }
        val placing = CivsRules.beginPlacement(ready, owner, order.id)
        val interrupted = CivsRules.pauseConstruction(placing, owner, order.id, "foreign-block-change")
        val retry = CivsRules.retryConstruction(interrupted, owner, order.id)
        retry.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
        val done = CivsRules.completeConstruction(CivsRules.beginPlacement(retry, owner, order.id), owner, order.id)
        done.stock shouldBe reserved.stock
        CivsRules.capacity(done) shouldBe 384L
        rejects("invalid-level") { CivsRules.reserveBuild(done, owner, warehouse, order) }
    }

    test("stale delivery and reassignment roundtrip never produce a duplicate resource") {
        val city = construct(activeCity(), building(3, BuildingType.FORESTER, x = 10))
        val assigned = CivsRules.assign(city, owner, uuid(101), uuid(3), 1_000)
        val worker = assigned.workers[1]
        rejects("delivery-too-early") { deliver(assigned, worker, worker.nextDeliveryAt - 1) }
        val delivered = deliver(assigned, worker)
        delivered.stock.wood shouldBe assigned.stock.wood + 4
        delivered.stock.food shouldBe assigned.stock.food
        delivered.workers[1].completedCycles shouldBe 1L
        delivered.workers[1].nextDeliveryAt shouldBe worker.nextDeliveryAt + 60_000
        rejects("stale-delivery") { deliver(delivered, worker, worker.nextDeliveryAt + 60_000) }
        val unassigned = CivsRules.assign(assigned, owner, worker.id, null, 1_000)
        rejects("unassigned") { deliver(unassigned, worker) }
        val reassigned = CivsRules.assign(unassigned, owner, worker.id, uuid(3), 1_000)
        rejects("stale-delivery") { deliver(reassigned, worker, reassigned.workers[1].nextDeliveryAt) }
        CivsRules.assign(reassigned, owner, worker.id, uuid(3), 1_000) shouldBe reassigned
        val late = deliver(assigned, worker, worker.nextDeliveryAt + 10 * 60_000)
        late.stock.wood shouldBe assigned.stock.wood + 4
        late.workers[1].nextDeliveryAt shouldBe worker.nextDeliveryAt + 11 * 60_000
    }

    test("full storage rejects the entire trip without consuming its token") {
        val city = construct(activeCity(), building(3, BuildingType.FORESTER, x = 10))
        val assigned = CivsRules.assign(city, owner, uuid(101), uuid(3), 1_000)
        val full = assigned.copy(stock = assigned.stock.copy(wood = 126))
        rejects("storage-full") { deliver(full, full.workers[1]) }
        full.workers[1].completedCycles shouldBe 0L
        val fits = full.copy(stock = full.stock.copy(wood = 124))
        deliver(fits, fits.workers[1]).stock.wood shouldBe 128L
    }

    test("only owner manages members and removed managers cannot submit delayed work") {
        val city = activeCity()
        val member = uuid(900)
        val outsider = uuid(901)
        val invited = CivsRules.addMember(city, owner, member)
        invited.revision shouldBe city.revision + 1
        CivsRules.addMember(invited, owner, member) shouldBe invited
        CivsRules.addMember(invited, owner, owner) shouldBe invited
        rejects("owner-required") { CivsRules.addMember(invited, member, outsider) }
        rejects("owner-cannot-be-removed") { CivsRules.removeMember(invited, owner, owner) }
        val warehouse = building(2, BuildingType.WAREHOUSE, x = 10)
        val order = orderFor(warehouse)
        rejects("not-authorized") { CivsRules.reserveBuild(city, outsider, warehouse, order) }
        val reserved = CivsRules.reserveBuild(invited, member, warehouse, order)
        val removed = CivsRules.removeMember(reserved, owner, member)
        rejects("not-authorized") { CivsRules.deliver(removed, member, removed.workers.first().id, hallId, 0, removed.workers.first().nextDeliveryAt, 50_000) }
        rejects("not-authorized") { CivsRules.pauseConstruction(removed, member, order.id, "blocked") }
        rejects("not-authorized") { CivsRules.beginPlacement(removed, member, order.id) }
        rejects("not-authorized") { CivsRules.completeConstruction(removed, member, order.id) }
        rejects("not-authorized") { CivsRules.retryConstruction(removed, member, order.id) }
        rejects("not-authorized") { CivsRules.assign(removed, member, uuid(101), null, 1_000) }
        rejects("not-authorized") { CivsRules.recruit(removed, member, uuid(999), "New") }
        CivsRules.removeMember(removed, owner, member) shouldBe removed
    }

    test("four pioneers reach level three using only real deliveries and reserved construction") {
        var city = activeCity()
        city = construct(city, building(2, BuildingType.WAREHOUSE, x = 10))
        city = construct(city, building(3, BuildingType.FORESTER, x = 20))
        city = construct(city, building(4, BuildingType.QUARRY, x = 30))
        city = construct(city, building(5, BuildingType.FARM, x = 40))
        city.stock shouldBe Stock(0, 40, 24)
        city = CivsRules.assign(city, owner, uuid(101), uuid(3), 1_000)
        city = CivsRules.assign(city, owner, uuid(102), uuid(4), 1_000)
        city = CivsRules.assign(city, owner, uuid(103), uuid(5), 1_000)
        rejects("population-full") { CivsRules.recruit(city, owner, uuid(104), "Fifth") }
        city = fund(city, CivsRules.cost(BuildingType.TOWN_HALL, 2))
        city = construct(city, city.building(hallId)!!.copy(level = 2, schematicId = "hall-2"))
        CivsRules.populationLimit(city) shouldBe 8
        rejects("no-beds") { CivsRules.recruit(city, owner, uuid(104), "Fifth") }
        val house = building(6, BuildingType.HOUSE, x = 50)
        city = fund(city, CivsRules.cost(house.type, 1))
        city = construct(city, house)
        city = fund(city, Stock(food = 12))
        val beforeRecruit = city.stock
        city = CivsRules.recruit(city, owner, uuid(104), "Fifth")
        city.stock shouldBe beforeRecruit.copy(food = beforeRecruit.food - 12)
        city = fund(city, CivsRules.cost(BuildingType.TOWN_HALL, 3))
        city = construct(city, city.building(hallId)!!.copy(level = 3, schematicId = "hall-3"))
        CivsRules.populationLimit(city) shouldBe 12
        for (buildingId in listOf(2, 3, 4, 5, 6)) {
            for (level in 2..3) {
                val upgrade = city.building(uuid(buildingId))!!.copy(level = level, schematicId = "building-$buildingId-$level")
                city = fund(city, CivsRules.cost(upgrade.type, level))
                val before = city
                val reserved = CivsRules.reserveBuild(city, owner, upgrade, orderFor(upgrade))
                reserved.building(upgrade.id)!!.level shouldBe level - 1
                CivsRules.capacity(reserved) shouldBe CivsRules.capacity(before)
                city = completeReady(workUntilReady(reserved))
                city.building(upgrade.id)!!.level shouldBe level
            }
        }
        CivsRules.capacity(city) shouldBe 1152L
        CivsRules.beds(city) shouldBe 10
        CivsRules.slots(city.building(uuid(3))!!) shouldBe 3
        CivsRules.deliveryAmount(city.building(uuid(3))!!) shouldBe 12L
        CivsRules.deliveryAmount(city.building(uuid(4))!!) shouldBe 9L
        CivsRules.deliveryAmount(city.building(uuid(5))!!) shouldBe 9L
        val secondHouse = building(7, BuildingType.HOUSE, x = 60)
        city = construct(fund(city, CivsRules.cost(secondHouse.type, 1)), secondHouse)
        CivsRules.beds(city) shouldBe 12
        for (workerId in 105..111) city = CivsRules.recruit(fund(city, Stock(food = 12)), owner, uuid(workerId), "Resident $workerId")
        city.workers.size shouldBe 12
        rejects("population-full") { CivsRules.recruit(city, owner, uuid(112), "Thirteenth") }
        city = CivsRules.assign(city, owner, uuid(104), uuid(3), 1_000)
        city = CivsRules.assign(city, owner, uuid(105), uuid(3), 1_000)
        rejects("workplace-full") { CivsRules.assign(city, owner, uuid(106), uuid(3), 1_000) }
        CivsRules.validate(city) shouldBe city
    }

    test("workplace limits geometry and a single construction are checked before charging") {
        var city = construct(activeCity(), building(3, BuildingType.FORESTER, x = 10))
        city = CivsRules.assign(city, owner, uuid(101), uuid(3), 1_000)
        rejects("workplace-full") { CivsRules.assign(city, owner, uuid(102), uuid(3), 1_000) }
        rejects("invalid-assignment") { CivsRules.assign(city, owner, uuid(102), hallId, 1_000) }
        rejects("invalid-building") { CivsRules.reserveBuild(city, owner, building(4, BuildingType.HOUSE, x = 10), orderFor(building(4, BuildingType.HOUSE, x = 10))) }
        rejects("invalid-building") { CivsRules.reserveBuild(city, owner, building(4, BuildingType.HOUSE, x = 101), orderFor(building(4, BuildingType.HOUSE, x = 101))) }
        val house = building(4, BuildingType.HOUSE, x = 20)
        val houseOrder = orderFor(house)
        rejects("invalid-construction") { CivsRules.reserveBuild(city, owner, house, houseOrder.copy(cost = Stock())) }
        rejects("invalid-construction") { CivsRules.reserveBuild(city, owner, house, houseOrder.copy(workRequired = 1)) }
        val reserved = CivsRules.reserveBuild(city, owner, house, houseOrder)
        val farm = building(5, BuildingType.FARM, x = 30)
        rejects("construction-busy") { CivsRules.reserveBuild(reserved, owner, farm, orderFor(farm)) }
        rejects("invalid-assignment") { CivsRules.assign(reserved, owner, uuid(102), house.id, 1_000) }
        rejects("invalid-construction") { CivsRules.reserveBuild(reserved, owner, house.copy(schematicId = "changed"), houseOrder) }
        val capped = city.copy(buildings = city.buildings + (4..17).map { building(it, BuildingType.HOUSE, x = (it - 3) * 5, z = 20, phase = BuildingPhase.ACTIVE) })
        CivsRules.validate(capped)
        val excess = building(30, BuildingType.FARM, x = 30, z = 40)
        rejects("building-limit") { CivsRules.reserveBuild(capped, owner, excess, orderFor(excess)) }
    }

    test("bootstrap reserve protects both missing producers without charging twice") {
        var city = construct(activeCity(), building(2, BuildingType.HOUSE, x = 10))
        city = construct(city, building(3, BuildingType.HOUSE, x = 20))
        city.stock shouldBe Stock(48, 72, 24)
        val thirdHouse = building(4, BuildingType.HOUSE, x = 30)
        rejects("resources.bootstrap-reserve") { CivsRules.reserveBuild(city, owner, thirdHouse, orderFor(thirdHouse)) }
        city = construct(city, building(5, BuildingType.QUARRY, x = 40))
        val farm = building(6, BuildingType.FARM, x = 50)
        rejects("resources.bootstrap-reserve") { CivsRules.reserveBuild(city, owner, farm, orderFor(farm)) }
        val forester = building(7, BuildingType.FORESTER, x = 60)
        val order = orderFor(forester)
        val reserved = CivsRules.reserveBuild(city, owner, forester, order)
        reserved.stock shouldBe Stock(8, 40, 24)
        CivsRules.reserveBuild(reserved, owner, forester, order) shouldBe reserved
        city = completeReady(workUntilReady(reserved))
        city = CivsRules.assign(city, owner, uuid(101), forester.id, 1_000)
        city = CivsRules.assign(city, owner, uuid(102), uuid(5), 1_000)
        city = construct(fund(city, CivsRules.cost(thirdHouse.type, 1)), thirdHouse)
        city.stock.wood shouldBe 0L
    }

    test("upgrades require town hall level and keep entrance and rotation while schematic origin may move") {
        var city = construct(activeCity(), building(3, BuildingType.FORESTER, x = 10))
        val original = city.building(uuid(3))!!
        val upgrade = original.copy(level = 2, schematicId = "forester-2", anchor = CivPosition(11, 64, 0))
        rejects("town-hall-level-required") { CivsRules.reserveBuild(city, owner, upgrade, orderFor(upgrade)) }
        city = city.copy(stock = Stock(128, 128, 24), buildings = city.buildings.map { if (it.id == hallId) it.copy(level = 2) else it })
        val movedEntrance = upgrade.copy(entrance = CivPosition(12, 64, 2))
        rejects("invalid-building") { CivsRules.reserveBuild(city, owner, movedEntrance, orderFor(movedEntrance)) }
        val rotated = upgrade.copy(rotation = 90)
        rejects("invalid-building") { CivsRules.reserveBuild(city, owner, rotated, orderFor(rotated)) }
        val reserved = CivsRules.reserveBuild(city, owner, upgrade, orderFor(upgrade))
        reserved.building(original.id) shouldBe original
        val restored = CivsRules.validate(Gson().fromJson(Gson().toJson(reserved), Settlement::class.java))
        val done = completeReady(workUntilReady(restored))
        done.building(original.id)!!.anchor shouldBe upgrade.anchor
        done.building(original.id)!!.entrance shouldBe original.entrance
        done.building(original.id)!!.level shouldBe 2
    }

    test("the last slots stay available for essential buildings and a full city can still upgrade") {
        var city = activeCity().let { base -> base.copy(buildings = base.buildings +
            building(3, BuildingType.FORESTER, x = 10, phase = BuildingPhase.ACTIVE) +
            building(4, BuildingType.QUARRY, x = 20, phase = BuildingPhase.ACTIVE) +
            (10..20).map { building(it, BuildingType.HOUSE, x = (it - 9) * 5, z = 20, phase = BuildingPhase.ACTIVE) }) }
        CivsRules.validate(city)
        city.buildings.size shouldBe 14
        val duplicateHouse = building(30, BuildingType.HOUSE, x = 70, z = 20)
        rejects("resources.bootstrap-slots") { CivsRules.reserveBuild(city, owner, duplicateHouse, orderFor(duplicateHouse)) }
        city = construct(city, building(31, BuildingType.WAREHOUSE, x = 70, z = 40))
        city.buildings.size shouldBe 15
        rejects("resources.bootstrap-slots") { CivsRules.reserveBuild(city, owner, duplicateHouse, orderFor(duplicateHouse)) }
        city = construct(city, building(32, BuildingType.FARM, x = 70, z = 60))
        city.buildings.size shouldBe 16
        rejects("building-limit") { CivsRules.reserveBuild(city, owner, duplicateHouse, orderFor(duplicateHouse)) }
        city = CivsRules.assign(city, owner, uuid(101), uuid(3), 1_000)
        city = CivsRules.assign(city, owner, uuid(102), uuid(4), 1_000)
        city = construct(fund(city, CivsRules.cost(BuildingType.TOWN_HALL, 2)), city.building(hallId)!!.copy(level = 2))
        val houseUpgrade = city.building(uuid(10))!!.copy(level = 2)
        city = construct(fund(city, CivsRules.cost(houseUpgrade.type, 2)), houseUpgrade)
        city.buildings.size shouldBe 16
        city.building(uuid(10))!!.level shouldBe 2
    }

    test("construction rejects routes beyond 88 blocks before reserving resources") {
        val city = activeCity()
        val distant = building(2, BuildingType.WAREHOUSE, x = 90)
        val stock = city.stock
        val revision = city.revision
        rejects("work-route-too-long") { CivsRules.reserveBuild(city, owner, distant, orderFor(distant)) }
        city.stock shouldBe stock
        city.revision shouldBe revision
        city.construction shouldBe null
        city.buildings.size shouldBe 1
        val boundary = building(2, BuildingType.WAREHOUSE, x = 88)
        val reserved = CivsRules.reserveBuild(city, owner, boundary, orderFor(boundary))
        reserved.stock shouldBe stock - CivsRules.cost(BuildingType.WAREHOUSE, 1)
        reserved.construction!!.buildingId shouldBe boundary.id
        val aboveBoundary = boundary.copy(entrance = boundary.entrance.copy(y = boundary.entrance.y + 1))
        rejects("work-route-too-long") { CivsRules.reserveBuild(city, owner, aboveBoundary, orderFor(aboveBoundary)) }
        val existing = building(4, BuildingType.FORESTER, x = 90, phase = BuildingPhase.ACTIVE)
        val oldCity = city.copy(stock = Stock(128, 128, 24),
            buildings = city.buildings.map { it.copy(level = 2) } + existing)
        val upgrade = existing.copy(level = 2, schematicId = "forester-2")
        rejects("work-route-too-long") { CivsRules.reserveBuild(oldCity, owner, upgrade, orderFor(upgrade)) }
    }

    test("only a completed nearby depot extends the construction route range") {
        val city = activeCity()
        val warehouse = building(2, BuildingType.WAREHOUSE, x = 60)
        val distant = building(3, BuildingType.HOUSE, x = 90)
        val unfinished = CivsRules.reserveBuild(city, owner, warehouse, orderFor(warehouse))
        CivsRules.workRouteWithinReach(unfinished, distant.entrance) shouldBe false
        rejects("construction-busy") { CivsRules.reserveBuild(unfinished, owner, distant, orderFor(distant)) }
        val completed = completeReady(workUntilReady(unfinished))
        CivsRules.workRouteWithinReach(completed, distant.entrance) shouldBe true
        val reserved = CivsRules.reserveBuild(completed, owner, distant, orderFor(distant))
        reserved.construction!!.buildingId shouldBe distant.id
        reserved.stock shouldBe completed.stock - CivsRules.cost(BuildingType.HOUSE, 1)
        val nonDepot = city.copy(buildings = city.buildings + warehouse.copy(type = BuildingType.HOUSE, phase = BuildingPhase.ACTIVE))
        CivsRules.workRouteWithinReach(nonDepot, distant.entrance) shouldBe false
    }

    test("invalid decoded state and overflowing counters fail closed") {
        val city = activeCity()
        val invalid = listOf(
            city.copy(stock = Stock(-1, 0, 0)),
            city.copy(stock = Stock(129, 0, 0)),
            city.copy(revision = -1),
            city.copy(workers = city.workers + city.workers.first()),
            city.copy(workers = city.workers.map { it.copy(assignment = uuid(666)) }),
            city.copy(buildings = city.buildings.map { it.copy(level = 4) }),
            city.copy(buildings = city.buildings.map { it.copy(phase = BuildingPhase.CONSTRUCTING) }),
            city.copy(members = setOf(owner)),
        )
        invalid.forEach { snapshot -> rejects("invalid-state") { CivsRules.validate(snapshot) } }
        rejects("invalid-state") { CivsRules.validate(Gson().fromJson("{}", Settlement::class.java)) }
        val nullWorkers = Gson().toJsonTree(city).asJsonObject.apply { add("workers", JsonNull.INSTANCE) }
        rejects("invalid-state") { CivsRules.validate(Gson().fromJson(nullWorkers, Settlement::class.java)) }
        val hall = city.building(hallId)!!
        val invalidConstruction = city.copy(construction = orderFor(hall.copy(level = 2)).copy(workDone = 7, plannedBuilding = hall.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)))
        rejects("invalid-state") { CivsRules.validate(invalidConstruction) }
        rejects("arithmetic-overflow") { CivsRules.addMember(city.copy(revision = Long.MAX_VALUE), owner, uuid(900)) }
        val unassigned = CivsRules.placeSite(CivsRules.assign(city, owner, uuid(100), null, 1_000), owner,
            building(55, BuildingType.BUILDER_HUT, x = 10))
        rejects("arithmetic-overflow") { CivsRules.assign(unassigned, owner, uuid(100), uuid(55), Long.MAX_VALUE) }
        val reserved = CivsRules.reserveBuild(city, owner, building(2, BuildingType.HOUSE, x = 10), orderFor(building(2, BuildingType.HOUSE, x = 10)))
        val exhausted = reserved.copy(workers = reserved.workers.mapIndexed { index, worker -> if (index == 0) worker.copy(completedCycles = Long.MAX_VALUE) else worker })
        rejects("arithmetic-overflow") { deliver(exhausted, exhausted.workers.first()) }
    }
})

private val owner = uuid(20)
private val hallId = uuid(1)
private val foundationId = uuid(40)
private fun uuid(value: Int) = UUID(0, value.toLong())

private fun building(id: Int, type: BuildingType, x: Int, z: Int = 0, phase: BuildingPhase = BuildingPhase.CONSTRUCTING): CivBuilding =
    CivBuilding(uuid(id), type, 1, CivPosition(x, 64, z), 0, "${type.name.lowercase()}-1",
        CivBox(CivPosition(x - 1, 64, z - 1), CivPosition(x + 1, 68, z + 1)), CivPosition(x, 64, z + 2), phase)

/** Exact pre-SITE contract, retained as a migration/recovery fixture. */
private fun foundedCity(): Settlement {
    val hall = building(1, BuildingType.TOWN_HALL, x = 0)
    return CivsRules.validate(Settlement(uuid(10), owner, "Settlement", uuid(30), CivPosition(0, 64, 0), 100,
        "medieval", Stock(96, 96, 24), listOf(hall), (100..103).map {
            CivWorker(uuid(it), "Pioneer $it", if (it == 100) hall.id else null, nextDeliveryAt = if (it == 100) 11_000 else 0)
        }, construction = CivConstruction(foundationId, hall.id, 1, 0, 0, ConstructionPhase.READY_TO_PLACE,
            Stock(), plannedBuilding = hall), createdAt = 1_000))
}

private fun activeCity(): Settlement = CivsRules.completeConstruction(CivsRules.beginPlacement(foundedCity(), owner, foundationId), owner, foundationId)

private fun orderFor(building: CivBuilding): CivConstruction = CivConstruction(uuid(1000 + building.id.leastSignificantBits.toInt() + building.level * 100),
    building.id, building.level, CivsRules.workRequired(building.type, building.level), 0, ConstructionPhase.CONSTRUCTING, CivsRules.cost(building.type, building.level))

private fun deliver(city: Settlement, worker: CivWorker, now: Long = worker.nextDeliveryAt): Settlement =
    CivsRules.deliver(city, owner, worker.id, worker.assignment ?: hallId, worker.completedCycles, worker.nextDeliveryAt, now)

private fun workUntilReady(initial: Settlement): Settlement {
    var city = initial
    while (city.construction!!.phase == ConstructionPhase.CONSTRUCTING) city = deliver(city, city.workers.first())
    return city
}

private fun completeReady(city: Settlement): Settlement {
    val id = city.construction!!.id
    return CivsRules.completeConstruction(CivsRules.beginPlacement(city, owner, id), owner, id)
}

private fun construct(city: Settlement, building: CivBuilding): Settlement =
    completeReady(workUntilReady(CivsRules.reserveBuild(city, owner, building, orderFor(building))))

private fun fund(initial: Settlement, required: Stock): Settlement {
    var city = initial
    for (resource in Resource.entries) {
        var rounds = 0
        while (city.stock[resource] < required[resource]) {
            check(rounds++ < 300) { "unreachable progression cost: $required" }
            val worker = city.workers.first { it.assignment?.let(city::building)?.type?.let(CivsRules::resource) == resource }
            city = deliver(city, worker)
        }
    }
    return city
}

private fun rejects(reason: String, action: () -> Unit) { shouldThrow<CivsRejection>(action).reason shouldBe reason }
