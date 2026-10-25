package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsRulesTest : FunSpec({
    test("plan board is one ordinary paid plot with no resident job or economic output") {
        val city = activeCity()
        val board = building(70, BuildingType.PLAN_BOARD, x = 10, phase = BuildingPhase.ACTIVE)
        BuildingType.PLAN_BOARD.isInfrastructure shouldBe false
        BuildingType.PLAN_BOARD.maxLevel shouldBe 1
        BuildingType.PLAN_BOARD.isResidence shouldBe false
        CivsRules.cost(BuildingType.PLAN_BOARD, 1) shouldBe Stock(12, 8)
        CivsAutonomy.cost(BuildingType.PLAN_BOARD, 1) shouldBe Stock(12, 8)
        rejects("invalid-level") { CivsRules.cost(BuildingType.PLAN_BOARD, 2) }
        CivsAutonomy.allowedTypes.contains(BuildingType.PLAN_BOARD) shouldBe true
        CivsAutonomy.profile.contains(BuildingType.PLAN_BOARD) shouldBe false
        CivsRules.slots(board) shouldBe 0
        CivsRules.resource(board.type) shouldBe null
        CivsRules.isDepot(board) shouldBe false
        CivsRules.productionAvailable(city, board) shouldBe false
        CivsProduction.options(board.type) shouldBe emptyList()
        val built = CivsRules.validate(city.copy(buildings = city.buildings + board))
        CivsRules.buildingCount(built) shouldBe CivsRules.buildingCount(city) + 1
        CivsRules.capacity(built) shouldBe CivsRules.capacity(city)
        CivsRules.beds(built) shouldBe CivsRules.beds(city)
        CivsRules.validate(Gson().fromJson(Gson().toJson(built), Settlement::class.java)) shouldBe built
        CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java)) shouldBe city
        val full = city.copy(buildings = city.buildings + (1..15).map {
            building(200 + it, BuildingType.PLAN_BOARD, x = (it % 5) * 6 + 10, z = (it / 5) * 6,
                phase = BuildingPhase.ACTIVE)
        })
        CivsRules.buildingCount(CivsRules.validate(full)) shouldBe CivsRules.MAX_BUILDINGS
        rejects("building-limit") { CivsRules.placeSite(full, owner, board.copy(id = uuid(999), anchor = board.anchor.copy(z = 30),
            bounds = CivBox(CivPosition(9, 64, 29), CivPosition(11, 68, 31)), entrance = CivPosition(10, 64, 32))) }
    }

    test("physical plan board reserves its exact captured bill and cannot use a legacy prepaid order") {
        val city = activeCity().copy(stock = Stock(), inventory = CivInventory.starting(Stock()))
        val board = building(70, BuildingType.PLAN_BOARD, x = 10)
        val site = CivsRules.placeSite(city, owner, board)
        val materials = mapOf("STRIPPED_SPRUCE_LOG" to 18L, "STONE_BRICKS" to 6L, "SMOOTH_SANDSTONE" to 15L,
            "DARK_OAK_STAIRS" to 14L, "DARK_OAK_SLAB" to 7L)
        val order = CivConstruction(uuid(710), board.id, 1, 4, 0, ConstructionPhase.CONSTRUCTING, Stock(),
            plannedBuilding = board, batches = CivConstructionBatches(4, 16, atomicPairs = true),
            materials = materials, payment = CivConstructionPayment())
        val reserved = CivsRules.reserveBuild(site, owner, board, order)
        reserved.stock shouldBe Stock()
        reserved.inventory shouldBe city.inventory
        reserved.construction!!.materials shouldBe materials
        reserved.construction!!.payment shouldBe CivConstructionPayment()
        CivsRules.reserveBuild(reserved, owner, board, order) shouldBe reserved
        rejects("invalid-construction") { CivsRules.reserveBuild(site, owner, board, order.copy(payment = null)) }
        rejects("invalid-construction") { CivsRules.reserveBuild(site, owner, board, order.copy(cost = Stock(12, 8))) }
        val auto = city.copy(stock = Stock(96, 96, 24), inventory = null, workers = emptyList(), autonomy = CivAutonomy())
        val autoSite = CivsRules.placeSite(auto, owner, board)
        val autoOrder = order.copy(cost = Stock(12, 8), payment = null)
        val paid = CivsRules.reserveBuild(autoSite, owner, board, autoOrder)
        paid.stock shouldBe Stock(84, 88, 24)
        CivsRules.reserveBuild(paid, owner, board, autoOrder) shouldBe paid
        paid.construction!!.materials shouldBe materials
    }

    test("resurrection restores the exact surviving belongings and skills with no work assignment or cargo") {
        val base = activeCity()
        val original = base.workers.first().copy(skills = mapOf("QUARRY" to 20L), homeId = base.buildings.first().id,
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("BREAD", 3) else null })
        val corpse = CivsCorpses.create(original, CivDeathContext(base.center, 10_000))
        val dead = base.copy(workers = base.workers.drop(1), corpses = listOf(corpse))
        val restored = CivsRules.resurrect(dead, owner, original.id, corpse.diedAt, 11_000)
        val resident = restored.workers.last()
        resident shouldBe original.copy(assignment = null, cargo = null, nextDeliveryAt = 0, health = 24.0)
        restored.corpses shouldBe emptyList()
        restored.stock shouldBe dead.stock
        restored.inventory shouldBe dead.inventory
        CivsRules.validate(Gson().fromJson(Gson().toJson(restored), Settlement::class.java)) shouldBe restored
        rejects("worker-not-found") { CivsRules.resurrect(restored, owner, original.id, corpse.diedAt, 11_000) }
        rejects("worker-not-found") { CivsRules.resurrect(dead, owner, original.id, corpse.diedAt + 1, 11_000) }
        rejects("corpse-expired") { CivsRules.resurrect(dead, owner, original.id, corpse.diedAt, corpse.expiresAt) }
        rejects("not-authorized") { CivsRules.resurrect(dead, uuid(999), original.id, corpse.diedAt, 11_000, administrator = true) }
        val full = dead.copy(workers = (0 until CivsRules.populationLimit(dead)).map { CivWorker(UUID.randomUUID(), "Other $it", null) })
        rejects("population-full") { CivsRules.resurrect(full, owner, original.id, corpse.diedAt, 11_000) }
        val auto = dead.copy(inventory = null, autonomy = CivAutonomy())
        rejects("not-authorized") { CivsRules.resurrect(auto, owner, original.id, corpse.diedAt, 11_000) }
        CivsRules.resurrect(auto, uuid(999), original.id, corpse.diedAt, 11_000, administrator = true).inventory shouldBe null
    }

    test("legacy snapshots keep testing off and settings survive the durable codec") {
        val city = activeCity()
        val legacy = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
        legacy.settings shouldBe null
        (legacy.settings ?: CivSettlementSettings()) shouldBe CivSettlementSettings(true, false, false)
        val configured = city.copy(settings = CivSettlementSettings(false, true, true))
        CivsRules.validate(Gson().fromJson(Gson().toJson(configured), Settlement::class.java)).settings shouldBe configured.settings
    }

    test("authorized immortality preserves resident belongings and ends when the permission decision is false") {
        val city = activeCity()
        val worker = city.workers.first()
        val protected = CivsRules.hurtResident(city, owner, worker.id, 100.0, immortal = true)
        protected.workers.first() shouldBe worker.copy(health = 1.0)
        protected.corpses shouldBe city.corpses
        val repeated = CivsRules.hurtResident(protected, owner, worker.id, 100.0, immortal = true)
        repeated shouldBe protected
        CivsRules.hurtResident(repeated, owner, worker.id, 100.0).workers.any { it.id == worker.id } shouldBe false
    }
    test("resident injuries and permanent deaths survive decoding even below four residents") {
        val city = activeCity()
        val worker = city.workers.first()
        val injured = CivsRules.hurtResident(city, owner, worker.id, 7.5)
        CivsResidents.health(injured.workers.first()) shouldBe 12.5
        val decoded = CivsRules.validate(Gson().fromJson(Gson().toJson(injured), Settlement::class.java))
        CivsResidents.health(decoded.workers.first()) shouldBe 12.5
        var empty = decoded
        for (resident in decoded.workers) empty = CivsRules.hurtResident(empty, owner, resident.id, 100.0)
        empty.workers shouldBe emptyList()
        CivsRules.validate(Gson().fromJson(Gson().toJson(empty), Settlement::class.java)).workers shouldBe emptyList()
        rejects("worker-not-found") { CivsRules.hurtResident(empty, owner, worker.id, 1.0) }
        rejects("not-authorized") { CivsRules.hurtResident(city, uuid(999), worker.id, 1.0) }
        rejects("invalid-damage") { CivsRules.hurtResident(city, owner, worker.id, Double.NaN) }
    }

    test("lost starter builders refill through timed immigration without a second item grant") {
        val started = CivsRules.found(uuid(10), owner, "Settlement", uuid(30), CivPosition(0, 64, 0),
            100, "medieval", building(1, BuildingType.TOWN_HALL, 0), 1_000)
        val resident = CivsRules.immigrate(started, owner, uuid(100), "Pioneer", 31_000)
        val lost = CivsRules.hurtResident(resident, owner, uuid(100), 20.0)
        rejects("population-natural") { CivsRules.recruitFunded(lost, owner, uuid(101), "Replacement") }
        val replacement = CivsRules.immigrate(lost, owner, uuid(101), "Replacement", 91_000)
        replacement.workers.size shouldBe 1
        replacement.immigration shouldBe CivImmigration(4, 151_000)
        replacement.inventory shouldBe lost.inventory
    }

    test("personal supplies enforce slot limits and only carried bread heals") {
        val city = activeCity()
        val id = city.workers.first().id
        var supplied = CivsRules.supplyResident(city, owner, id, "LEATHER_HELMET")
        rejects("resident-slot-occupied") { CivsRules.supplyResident(supplied, owner, id, "IRON_HELMET") }
        supplied = CivsRules.supplyResident(supplied, owner, id, "LEATHER_HELMET", returning = true)
        supplied = CivsRules.supplyResident(supplied, owner, id, "IRON_HELMET")
        rejects("invalid-resident-item") { CivsRules.supplyResident(supplied, owner, id, "DIAMOND") }
        repeat(16) { supplied = CivsRules.supplyResident(supplied, owner, id, "BREAD") }
        rejects("resident-inventory-full") { CivsRules.supplyResident(supplied, owner, id, "BREAD") }
        rejects("resident-healthy") { CivsRules.feedResident(supplied, owner, id) }
        val injured = CivsRules.hurtResident(supplied, owner, id, 10.0)
        val healed = CivsRules.feedResident(injured, owner, id)
        CivsResidents.health(healed.workers.first()) shouldBe 16.0
        CivsEquipment.plainCount(healed.workers.first(), "BREAD") shouldBe 15
        CivsRules.hurtResident(healed, owner, id, 100.0).workers.none { it.id == id } shouldBe true
    }

    test("new profession experience grants bounded endurance without rewriting old cycles or production rates") {
        var worker = activeCity().workers.first().copy(completedCycles = 1_000)
        CivsResidents.maxHealth(worker) shouldBe 20.0
        repeat(10) { worker = CivsResidents.trained(worker, BuildingType.FORESTER) }
        CivsResidents.skillLevel(worker, "FORESTER") shouldBe 2
        CivsResidents.skillLevel(worker, "QUARRY") shouldBe 1
        CivsResidents.maxHealth(worker) shouldBe 22.0
        repeat(100) { worker = CivsResidents.trained(worker, BuildingType.FORESTER) }
        worker.skills?.get("FORESTER") shouldBe 40L
        CivsResidents.maxHealth(worker) shouldBe 28.0
        CivsResidents.damage(worker, BuildingType.FORESTER, 10.0) shouldBe 10.0
        CivsResidents.damage(worker.copy(inventory = mapOf("IRON_AXE" to 1)), BuildingType.FORESTER, 10.0) shouldBe 9.0
        CivsResidents.damage(worker.copy(inventory = mapOf("IRON_AXE" to 1)), BuildingType.QUARRY, 10.0) shouldBe 10.0
    }

    test("invalid saved resident health inventory and skills fail closed") {
        val city = activeCity()
        for (worker in listOf(city.workers.first().copy(health = 0.0), city.workers.first().copy(health = 21.0),
            city.workers.first().copy(skills = mapOf("QUARRY" to -1)),
            city.workers.first().copy(inventory = mapOf("BREAD" to 17)),
            city.workers.first().copy(inventory = mapOf("IRON_HELMET" to 1, "LEATHER_HELMET" to 1)))) {
            rejects("invalid-state") { CivsRules.validate(city.copy(workers = listOf(worker))) }
        }
    }

    test("automatic jobs fill the builder site first and preserve manual assignments and delivery tokens") {
        val farm = building(2, BuildingType.FARM, 8, phase = BuildingPhase.ACTIVE)
        val hut = building(3, BuildingType.BUILDER_HUT, 16, phase = BuildingPhase.SITE)
        val quarry = building(4, BuildingType.QUARRY, 24, phase = BuildingPhase.ACTIVE)
        val unavailable = building(5, BuildingType.FORESTER, 32, phase = BuildingPhase.SITE)
        val city = activeCity().let { it.copy(stock = Stock(), inventory = CivInventory.starting(it.stock),
            buildings = it.buildings + listOf(farm, hut, quarry, unavailable),
            workers = it.workers.mapIndexed { index, worker -> worker.copy(assignment = if (index == 3) farm.id else null,
                nextDeliveryAt = if (index == 3) 1_000 else worker.nextDeliveryAt) }) }
        CivsRules.needsAutoAssignment(city) shouldBe true
        val assigned = CivsRules.autoAssign(city, owner, 20_000)
        assigned.revision shouldBe city.revision + 1
        assigned.workers.map { it.assignment } shouldBe listOf(hut.id, quarry.id, city.buildings.first().id, farm.id)
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
        val named = invited.copy(memberNames = mapOf(owner.toString() to "GrocerMC", member.toString() to "Member"))
        val reserved = CivsRules.reserveBuild(named, member, warehouse, order)
        val removed = CivsRules.removeMember(reserved, owner, member)
        removed.memberNames shouldBe mapOf(owner.toString() to "GrocerMC")
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
        rejects("population-natural") { CivsRules.recruit(city, owner, uuid(104), "Fifth") }
        city = fund(city, CivsRules.cost(BuildingType.TOWN_HALL, 2))
        city = construct(city, city.building(hallId)!!.copy(level = 2, schematicId = "hall-2"))
        CivsRules.populationLimit(city) shouldBe 8
        CivsRules.beds(city) shouldBe 5
        val house = building(6, BuildingType.HOUSE, x = 50)
        city = fund(city, CivsRules.cost(house.type, 1))
        city = construct(city, house)
        val beforeArrival = city.stock
        city = CivsRules.immigrate(city, owner, uuid(104), "Fifth", 1_000_000)
        city.stock shouldBe beforeArrival
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
        CivsRules.beds(city) shouldBe 11
        CivsRules.slots(city.building(uuid(3))!!) shouldBe 3
        CivsRules.deliveryAmount(city.building(uuid(3))!!) shouldBe 12L
        CivsRules.deliveryAmount(city.building(uuid(4))!!) shouldBe 9L
        CivsRules.deliveryAmount(city.building(uuid(5))!!) shouldBe 9L
        val secondHouse = building(7, BuildingType.HOUSE, x = 60)
        city = construct(fund(city, CivsRules.cost(secondHouse.type, 1)), secondHouse)
        CivsRules.beds(city) shouldBe 14
        for (level in 2..3) {
            val upgrade = city.building(secondHouse.id)!!.copy(level = level, schematicId = "house-$level")
            city = construct(fund(city, CivsRules.cost(upgrade.type, level)), upgrade)
        }
        var nextResident = 105
        repeat(80) {
            city = CivsPopulation.advance(city, emptySet(), { _, id, _ -> "Resident ${id.leastSignificantBits}" },
                { uuid(nextResident++) })
        }
        city.workers.size shouldBe 12
        CivsRules.immigrate(city, owner, uuid(112), "Thirteenth", 1_000_000) shouldBe city
        rejects("population-natural") { CivsRules.recruit(city, owner, uuid(112), "Thirteenth") }
        city = CivsRules.assign(city, owner, uuid(104), uuid(3), 1_000)
        city = CivsRules.assign(city, owner, uuid(105), uuid(3), 1_000)
        rejects("workplace-full") { CivsRules.assign(city, owner, uuid(106), uuid(3), 1_000) }
        CivsRules.validate(city) shouldBe city
    }

    test("workplace limits geometry and a single construction are checked before charging") {
        var city = construct(activeCity(), building(3, BuildingType.FORESTER, x = 10))
        city = CivsRules.assign(city, owner, uuid(101), uuid(3), 1_000)
        rejects("workplace-full") { CivsRules.assign(city, owner, uuid(102), uuid(3), 1_000) }
        rejects("workplace-full") { CivsRules.assign(city, owner, uuid(102), hallId, 1_000) }
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
            city.copy(stock = Stock(128L + (CivsRules.MAX_BUILDINGS - 1) * 1024L + 1, 0, 0)),
            city.copy(revision = -1),
            city.copy(workers = city.workers + city.workers.first()),
            city.copy(workers = city.workers.map { it.copy(assignment = uuid(666)) }),
            city.copy(buildings = city.buildings.map { it.copy(level = 4) }),
            city.copy(buildings = city.buildings.map { it.copy(phase = BuildingPhase.CONSTRUCTING) }),
            city.copy(members = setOf(owner)),
            city.copy(memberNames = mapOf(uuid(900).toString() to "Outsider")),
            city.copy(memberNames = mapOf(owner.toString() to " ")),
            city.copy(memberNames = mapOf(owner.toString() to "A".repeat(33))),
            city.copy(memberNames = mapOf(owner.toString() to "Bad\nName")),
        )
        invalid.forEach { snapshot -> rejects("invalid-state") { CivsRules.validate(snapshot) } }
        val named = city.copy(memberNames = mapOf(owner.toString() to "GrocerMC"))
        CivsRules.validate(named) shouldBe named
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
