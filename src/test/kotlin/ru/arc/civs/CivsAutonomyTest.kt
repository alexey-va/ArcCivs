package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomyTest : FunSpec({
    test("the infrastructure cap leaves ordinary village upgrades eligible") {
        val base = autonomyCity().copy(buildings = (CivsAutonomy.profile + listOf(BuildingType.HOUSE, BuildingType.HOUSE))
            .map { autonomyBuilding(it).copy(phase = BuildingPhase.ACTIVE) })
        val upgrade = requireNotNull(CivsAutonomy.nextGoal(base))
        upgrade.type shouldBe BuildingType.TOWN_HALL
        upgrade.targetLevel shouldBe 2
        CivsAutonomy.infrastructureDue(base, upgrade) shouldBe true
        val capped = base.copy(buildings = base.buildings + List(CivsRules.MAX_INFRASTRUCTURE) {
            autonomyBuilding(BuildingType.ROAD).copy(phase = BuildingPhase.ACTIVE)
        })
        CivsAutonomy.infrastructureDue(capped, CivsAutonomy.nextGoal(capped)) shouldBe false
        CivsAutonomy.nextGoal(capped) shouldBe upgrade
    }

    test("legacy cities remain personal and autonomous snapshots reject physical inventory and membership") {
        val city = autonomyCity()
        val personal = CivsRules.found(city.id, city.owner, city.name, city.worldId, city.center,
            city.radius, city.style, city.buildings.single(), 1_000)
        autonomyRoundtrip(personal).autonomy shouldBe null
        autonomyRoundtrip(city) shouldBe city
        city.stock shouldBe Stock(96, 96, 24)
        for (invalid in listOf(city.copy(inventory = CivInventory.starting(Stock())),
            city.copy(members = setOf(UUID.randomUUID())), city.copy(autonomy = CivAutonomy(recruitmentMinutes = 3)))) {
            autonomyReject("invalid-state") { CivsRules.validate(invalid) }
        }
        CivsMembership.participants(city) shouldBe emptySet()
        CivsMembership.participants(personal) shouldBe setOf(personal.owner)
    }

    test("one deterministic goal bootstraps the hut before the hall and reports exact shortages") {
        var city = autonomyCity()
        CivsAutonomy.nextGoal(city) shouldBe CivAutonomyGoal(BuildingType.BUILDER_HUT, 1, null, Stock(16, 8), Stock())
        val hut = autonomyBuilding(BuildingType.BUILDER_HUT)
        city = CivsRules.placeSite(city, city.owner, hut)
        CivsAutonomy.nextGoal(city)!!.buildingId shouldBe hut.id
        val active = city.copy(buildings = city.buildings.map { if (it.id == hut.id) it.copy(phase = BuildingPhase.ACTIVE) else it },
            stock = Stock(19, 10, 0))
        CivsAutonomy.nextGoal(active) shouldBe CivAutonomyGoal(BuildingType.FORESTER, 1, null, Stock(20, 12), Stock(1, 2))
        val quarry = autonomyBuilding(BuildingType.QUARRY)
        val queued = CivsRules.placeSite(active, active.owner, quarry)
        CivsAutonomy.nextGoal(queued)!!.buildingId shouldBe quarry.id
        CivsAutonomy.nextGoal(CivsAutonomy.setPaused(queued, true)) shouldBe null
        CivsAutonomy.cost(BuildingType.TOWN_HALL, 1) shouldBe Stock(32, 24, 4)
        CivsAutonomy.cost(BuildingType.BUILDER_HUT, 2) shouldBe Stock(64, 32)
    }

    test("captured construction charges complete stock once across retries restart and credited batches") {
        var city = autonomyImmigrants(autonomyCity())
        val hut = autonomyBuilding(BuildingType.BUILDER_HUT)
        city = CivsRules.placeSite(city, city.owner, hut)
        city = CivsRules.autoAssign(city, city.owner, 300_000)
        val plan = hut.copy(phase = BuildingPhase.CONSTRUCTING)
        val order = autonomyOrder(plan, 65)
        val before = city.stock
        city = CivsRules.reserveBuild(city, city.owner, plan, order)
        city.stock shouldBe before - Stock(16, 8)
        CivsRules.reserveBuild(city, city.owner, plan, order) shouldBe city
        city = autonomyRoundtrip(city)
        val worker = city.workers.first { it.assignment == hut.id }
        city = autonomyTrip(city, worker, worker.nextDeliveryAt)
        city.construction!!.workCredit shouldBe 64
        city.workers.first { it.id == worker.id }.nextDeliveryAt shouldBe worker.nextDeliveryAt + 2_000
        city.construction!!.payment shouldBe null
        CivsRules.reserveBuild(city, city.owner, plan, order) shouldBe city
        autonomyReject("stale-delivery") { autonomyTrip(city, worker, worker.nextDeliveryAt + 2_000) }
        city = autonomyRoundtrip(city)
        city = CivsRules.completeBatch(CivsRules.beginPlacement(city, city.owner, order.id), city.owner, order.id)
        city.construction!!.workDone shouldBe 1
        city.construction!!.workCredit shouldBe 63
        CivsRules.reserveBuild(city, city.owner, plan, order) shouldBe city
        val paused = CivsAutonomy.setPaused(city, true)
        autonomyReject("autonomy-paused") { autonomyTrip(paused, paused.workers.first(), 400_000) }
        city.stock shouldBe before - Stock(16, 8)
        city.workers.all { it.items == null && it.inventory == null && it.cargo == null } shouldBe true
        val unpaidPlayer = city.copy(autonomy = null, construction = city.construction!!.copy(workCredit = null))
        autonomyReject("invalid-state") { CivsRules.validate(unpaidPlayer) }
    }

    test("initial immigration is a durable five-admission budget and subsequent replacements consume food") {
        var city = autonomyImmigrants(autonomyCity())
        city.immigration!!.remaining shouldBe 0
        city = CivsRules.hurtResident(city, city.owner, city.workers.last().id, 100.0)
        CivsRules.immigrationDue(city, Long.MAX_VALUE) shouldBe false
        val before = city.stock
        city = CivsAutonomy.activeMinute(city)
        city.workers.size shouldBe 4
        city.autonomy!!.recruitmentMinutes shouldBe 1
        city = CivsAutonomy.activeMinute(autonomyRoundtrip(city))
        city.workers.size shouldBe 5
        city.stock shouldBe before.copy(food = before.food - 12)
        CivsAutonomy.activeMinute(city) shouldBe city
        val fresh = CivsAutonomy.setPaused(autonomyCity(), true)
        CivsRules.immigrationDue(fresh, Long.MAX_VALUE) shouldBe false
        autonomyReject("autonomy-paused") { CivsRules.immigrate(fresh, fresh.owner, UUID.randomUUID(), "Resident", Long.MAX_VALUE) }
    }

    test("active-minute meals consume one abstract food each and pause or shortage creates no items") {
        val base = autonomyImmigrants(autonomyCity())
        val hungry = base.copy(stock = Stock(food = 2), workers = base.workers.map { it.copy(hunger = 14) })
        val fed = CivsAutonomy.activeMinute(hungry)
        fed.stock shouldBe Stock()
        fed.workers.map(CivsResidents::hunger) shouldBe listOf(20, 20, 14, 14, 14)
        fed.workers.all { it.items == null && it.inventory == null } shouldBe true
        CivsAutonomy.activeMinute(fed) shouldBe fed
        val paused = CivsAutonomy.setPaused(hungry, true)
        CivsAutonomy.activeMinute(paused) shouldBe paused
        val noFood = base.copy(stock = Stock(), workers = base.workers.dropLast(1), autonomy = CivAutonomy(recruitmentMinutes = 1))
        val waiting = CivsAutonomy.activeMinute(noFood)
        waiting.workers shouldBe noFood.workers
        waiting.autonomy!!.recruitmentMinutes shouldBe 2
        CivsAutonomy.activeMinute(autonomyRoundtrip(waiting)) shouldBe waiting
    }

    test("essential jobs precede the guard and second producer slots") {
        val buildings = CivsAutonomy.profile.map { autonomyBuilding(it, 2, BuildingPhase.ACTIVE) }
        val hall = buildings.first { it.type == BuildingType.TOWN_HALL }
        val house = buildings.first { it.type == BuildingType.HOUSE }
        val workers = (0..7).map { index -> CivWorker(UUID(50, index.toLong()), "Resident $index", null,
            homeId = if (index < 5) hall.id else house.id, hunger = 20) }
        val city = CivsRules.validate(autonomyCity().copy(buildings = buildings, workers = workers,
            immigration = CivImmigration(0, 1_000)))
        val assigned = CivsRules.autoAssign(city, city.owner, 300_000)
        assigned.workers.map { worker -> assigned.building(worker.assignment!!)!!.type } shouldBe listOf(
            BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM,
            BuildingType.GUARD_TOWER, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM)
        CivsRules.slots(city, hall) shouldBe 0
        CivsRules.slots(city, buildings.first { it.type == BuildingType.WAREHOUSE }) shouldBe 0
        CivsRules.isBuilder(city, hall) shouldBe false
        CivsRules.autoAssign(assigned, city.owner, 400_000) shouldBe assigned
    }

    test("real trip receipts sustain eleven buildings including three houses through level two without outside inputs") {
        var city = autonomyImmigrants(autonomyCity())
        var now = 300_000L
        val completed = mutableListOf<Pair<BuildingType, Int>>()
        var minutes = 0
        while (CivsAutonomy.nextGoal(city) != null && minutes++ < 1_000) {
            val goal = requireNotNull(CivsAutonomy.nextGoal(city))
            if (goal.ready) {
                val building = goal.buildingId?.let(city::building)?.copy(level = goal.targetLevel,
                    schematicId = "autonomy/${goal.type.name.lowercase()}/${goal.targetLevel}", phase = BuildingPhase.CONSTRUCTING)
                    ?: autonomyBuilding(goal.type, occurrence = city.buildings.count { it.type == goal.type }).also { city = CivsRules.placeSite(city, city.owner, it) }
                        .copy(phase = BuildingPhase.CONSTRUCTING)
                city = CivsRules.autoAssign(city, city.owner, now)
                val order = autonomyOrder(building, 65)
                city = CivsRules.reserveBuild(city, city.owner, building, order)
                while (city.construction != null) {
                    if (city.construction!!.phase == ConstructionPhase.CONSTRUCTING) {
                        val builder = city.workers.first { it.assignment?.let(city::building)?.let { job -> CivsRules.isBuilder(city, job) } == true }
                        now = maxOf(now, builder.nextDeliveryAt)
                        city = autonomyTrip(city, builder, now)
                    }
                    city = CivsRules.completeBatch(CivsRules.beginPlacement(city, city.owner, order.id), city.owner, order.id)
                }
                completed += goal.type to goal.targetLevel
                city = autonomyRoundtrip(city)
            }
            now += 60_000
            city = CivsRules.autoAssign(city, city.owner, now)
            for (id in city.workers.map { it.id }) {
                val worker = city.workers.first { it.id == id }
                val job = worker.assignment?.let(city::building) ?: continue
                val resource = CivsRules.resource(job.type) ?: continue
                if (now >= worker.nextDeliveryAt && CivsRules.productionAvailable(city, job) &&
                    CivsRules.capacity(city) - city.stock[resource] >= CivsRules.deliveryAmount(job)) city = autonomyTrip(city, worker, now)
            }
            city = CivsPopulation.advance(city, city.workers.filter { it.assignment != null }.mapTo(hashSetOf()) { it.id }, CivsNames::name)
            city = CivsAutonomy.activeMinute(city)
        }
        (minutes < 1_000) shouldBe true
        completed.take(9).map { it.first } shouldBe CivsAutonomy.profile
        completed.size shouldBe 22
        CivsAutonomy.nextGoal(city) shouldBe null
        city.buildings.size shouldBe 11
        city.buildings.all { it.level == 2 && it.phase == BuildingPhase.ACTIVE } shouldBe true
        city.workers.size shouldBe minOf(CivsRules.populationLimit(city), CivsRules.beds(city))
        city.inventory shouldBe null
        city.workers.all { it.items == null && it.inventory == null && it.cargo == null } shouldBe true
        Resource.entries.all { city.stock[it] >= 0 } shouldBe true
    }
})

private fun autonomyCity(): Settlement = CivsAutonomy.found(UUID.randomUUID(), UUID.randomUUID(), "Autonomous village",
    UUID.randomUUID(), CivPosition(0, 64, 0), 128, "medievaloak", autonomyBuilding(BuildingType.TOWN_HALL), 1_000)

private fun autonomyBuilding(type: BuildingType, level: Int = 1, phase: BuildingPhase = BuildingPhase.SITE,
    occurrence: Int = 0): CivBuilding {
    val x = type.ordinal * 8; val z = occurrence * 12
    return CivBuilding(UUID(1 + occurrence.toLong(), type.ordinal.toLong()), type, level, CivPosition(x, 64, z), 0,
        "autonomy/${type.name.lowercase()}/$level", CivBox(CivPosition(x - 2, 62, z - 2), CivPosition(x + 2, 68, z + 2)),
        CivPosition(x, 64, z - 3), phase, CivPosition(x, 64, z))
}

private fun autonomyImmigrants(before: Settlement): Settlement {
    var city = before
    repeat(5) { index -> city = CivsRules.immigrate(city, city.owner, UUID(2, index.toLong()),
        "Resident $index", 31_000L + index * 60_000) }
    return city
}

private fun autonomyOrder(building: CivBuilding, rows: Int) = CivConstruction(UUID.randomUUID(), building.id, building.level,
    rows, 0, ConstructionPhase.CONSTRUCTING, CivsAutonomy.cost(building.type, building.level), plannedBuilding = building,
    batches = CivConstructionBatches(rows, 16, true), materials = mapOf("STONE_BRICKS" to 999L, "BOOK" to 20L),
    blocksRequired = rows, blocksPlaced = 0)

private fun autonomyTrip(city: Settlement, worker: CivWorker, now: Long): Settlement = CivsRules.deliver(city, city.owner,
    worker.id, requireNotNull(worker.assignment), worker.completedCycles, worker.nextDeliveryAt, now)

private fun autonomyRoundtrip(city: Settlement): Settlement = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))

private fun autonomyReject(reason: String, action: () -> Unit) {
    shouldThrow<CivsRejection>(action).reason shouldBe reason
}
