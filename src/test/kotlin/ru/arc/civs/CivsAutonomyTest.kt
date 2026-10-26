package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomyTest : FunSpec({
    test("the infrastructure cap leaves ordinary village upgrades eligible") {
        val base = autonomyCity().copy(buildings = (CivsAutonomy.baseProfile + listOf(BuildingType.HOUSE, BuildingType.HOUSE))
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

    test("the plan board follows the first house and existing boards are never duplicated") {
        val beforeBoard = CivsAutonomy.baseProfile.takeWhile { it != BuildingType.PLAN_BOARD }
            .map { autonomyBuilding(it, phase = BuildingPhase.ACTIVE) }
        val city = autonomyCity().copy(buildings = beforeBoard, stock = Stock(4, 20))
        CivsAutonomy.nextGoal(city) shouldBe CivAutonomyGoal(BuildingType.PLAN_BOARD, 1, null, Stock(12, 8), Stock(8))
        val board = autonomyBuilding(BuildingType.PLAN_BOARD)
        val queued = city.copy(buildings = city.buildings + board, constructionQueue = listOf(board.id))
        CivsAutonomy.nextGoal(queued)?.buildingId shouldBe board.id
        val complete = city.copy(buildings = city.buildings + board.copy(phase = BuildingPhase.ACTIVE))
        CivsAutonomy.nextGoal(complete)?.type shouldBe BuildingType.TAVERN
    }

    test("a full established village without a board still advances upgrades and infrastructure") {
        val types = CivsAutonomy.baseProfile.filterNot { it == BuildingType.PLAN_BOARD } +
            listOf(BuildingType.HOUSE, BuildingType.HOUSE, BuildingType.UNIVERSITY, BuildingType.SMELTERY,
                BuildingType.CRUSHER, BuildingType.BLACKSMITH, BuildingType.BAKERY)
        types.size shouldBe CivsRules.MAX_BUILDINGS
        val city = autonomyCity().copy(buildings = types.mapIndexed { index, type ->
            autonomyBuilding(type, phase = BuildingPhase.ACTIVE, occurrence = index)
        })
        val goal = requireNotNull(CivsAutonomy.nextGoal(city))
        goal.type shouldBe BuildingType.TOWN_HALL
        goal.targetLevel shouldBe 2
        CivsAutonomy.infrastructureDue(city, goal) shouldBe true
    }

    test("the next-plan view projects only the accepted current construction without spending or mutating") {
        val beforeHouse = CivsAutonomy.baseProfile.takeWhile { it != BuildingType.HOUSE }
            .map { autonomyBuilding(it, phase = BuildingPhase.ACTIVE) }
        val house = autonomyBuilding(BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING)
        val city = autonomyCity().copy(buildings = beforeHouse + house, stock = Stock(4, 20),
            construction = autonomyOrder(house, 65), autonomy = CivAutonomy(paused = true))
        val unchanged = autonomyRoundtrip(city)
        CivsAutonomy.nextGoal(city) shouldBe null
        CivsAutonomy.nextGoalAfterCurrent(city) shouldBe
            CivAutonomyGoal(BuildingType.PLAN_BOARD, 1, null, Stock(12, 8), Stock(8))
        city shouldBe unchanged
        CivsAutonomy.nextGoalAfterCurrent(city.copy(autonomy = null)) shouldBe null
        CivsAutonomy.nextGoalAfterCurrent(city.copy(pendingDeletion = CivDeletion(null))) shouldBe null
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
        val buildings = CivsAutonomy.baseProfile.map { autonomyBuilding(it, minOf(2, it.maxLevel), BuildingPhase.ACTIVE) }
        val hall = buildings.first { it.type == BuildingType.TOWN_HALL }
        val house = buildings.first { it.type == BuildingType.HOUSE }
        val workers = (0..7).map { index -> CivWorker(UUID(50, index.toLong()), "Resident $index", null,
            homeId = if (index < 5) hall.id else house.id, hunger = 20) }
        val city = CivsRules.validate(autonomyCity().copy(buildings = buildings, workers = workers,
            immigration = CivImmigration(0, 1_000)))
        val assigned = CivsRules.autoAssign(city, city.owner, 300_000)
        assigned.workers.map { worker -> assigned.building(worker.assignment!!)!!.type } shouldBe listOf(
            BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM,
            BuildingType.WAREHOUSE, BuildingType.GUARD_TOWER, BuildingType.FORESTER, BuildingType.QUARRY)
        CivsRules.slots(city, hall) shouldBe 0
        CivsRules.slots(city, buildings.first { it.type == BuildingType.WAREHOUSE }) shouldBe 2
        CivsRules.isBuilder(city, hall) shouldBe false
        CivsRules.autoAssign(assigned, city.owner, 400_000) shouldBe assigned
    }

    test("the last idle forester resumes paid farm construction without resetting its receipt or granting stock") {
        val city = autonomyLostBuilder()
        val worker = city.workers.single()
        val hut = city.buildings.first { it.type == BuildingType.BUILDER_HUT }
        city.stock shouldBe Stock(68, 48, 3)
        CivsAutonomy.activeMinute(city).workers shouldBe city.workers // Twelve food is still required.
        CivsRules.needsAutoAssignment(city) shouldBe true
        val assigned = CivsRules.autoAssign(autonomyRoundtrip(city), city.owner, worker.nextDeliveryAt)
        assigned.workers.single() shouldBe worker.copy(assignment = hut.id,
            nextDeliveryAt = worker.nextDeliveryAt + CivsRules.BUILDER_SESSION_INTERVAL)
        assigned.stock shouldBe city.stock
        assigned.construction shouldBe city.construction
        assigned.immigration shouldBe city.immigration
        assigned.autonomy shouldBe city.autonomy
        CivsRules.autoAssign(assigned, city.owner, 400_000) shouldBe assigned
        autonomyReject("stale-delivery") { autonomyTrip(assigned, worker, worker.nextDeliveryAt) }
    }

    test("the last builder staffs the completed farm and replacements still cost twelve earned food") {
        val unfinished = autonomyLostBuilder()
        val farm = unfinished.buildings.first { it.type == BuildingType.FARM }
        val hut = unfinished.buildings.first { it.type == BuildingType.BUILDER_HUT }
        val before = CivsRules.validate(unfinished.copy(construction = null,
            buildings = unfinished.buildings.map { if (it.id == farm.id) it.copy(phase = BuildingPhase.ACTIVE) else it },
            workers = listOf(unfinished.workers.single().copy(assignment = hut.id))))
        var city = CivsRules.autoAssign(before, before.owner, 300_000)
        city.workers.single().assignment shouldBe farm.id
        city.stock shouldBe before.stock
        city.workers.single().health shouldBe before.workers.single().health
        CivsRules.autoAssign(city, city.owner, 300_001) shouldBe city

        val warehouse = autonomyBuilding(BuildingType.WAREHOUSE, phase = BuildingPhase.CONSTRUCTING)
        val queued = CivsRules.reserveBuild(city, city.owner, warehouse, autonomyOrder(warehouse, 65))
        CivsRules.autoAssign(queued, queued.owner, 300_002) shouldBe queued // Do not pull the only farmer back before recruitment.
        repeat(3) {
            val worker = city.workers.single()
            city = autonomyTrip(city, worker, worker.nextDeliveryAt)
        }
        city.stock.food shouldBe CivsRules.RECRUIT_FOOD
        val recruited = CivsAutonomy.activeMinute(autonomyRoundtrip(city), nextId = { UUID(20, 1) })
        recruited.workers.size shouldBe 2
        recruited.workers.first().id shouldBe before.workers.single().id
        recruited.stock shouldBe city.stock.copy(food = 0)
        val staffed = CivsRules.autoAssign(recruited, recruited.owner, 600_000)
        staffed.workers.map { it.assignment } shouldBe listOf(farm.id, hut.id)
    }

    test("a staffed pending order leaves an idle forester available for the empty low food farm") {
        val unfinished = autonomyLostBuilder()
        val farm = unfinished.buildings.first { it.type == BuildingType.FARM }
        val hut = unfinished.buildings.first { it.type == BuildingType.BUILDER_HUT }
        val forester = unfinished.workers.single()
        val builder = forester.copy(id = UUID(21, 1), name = "Builder", assignment = hut.id, health = 20.0)
        val completedFarm = CivsRules.validate(unfinished.copy(construction = null, workers = listOf(builder, forester),
            buildings = unfinished.buildings.map { if (it.id == farm.id) it.copy(phase = BuildingPhase.ACTIVE) else it }))
        val warehouse = autonomyBuilding(BuildingType.WAREHOUSE, phase = BuildingPhase.CONSTRUCTING)
        val pending = CivsRules.reserveBuild(completedFarm, completedFarm.owner, warehouse, autonomyOrder(warehouse, 65))
        for (city in listOf(pending, CivsRules.pauseConstruction(pending, pending.owner, pending.construction!!.id, "world-changed"))) {
            CivsRules.needsAutoAssignment(city) shouldBe true
            val staffed = CivsRules.autoAssign(autonomyRoundtrip(city), city.owner, 400_000)
            staffed.workers.first() shouldBe builder
            staffed.workers.last().assignment shouldBe farm.id
            staffed.stock shouldBe city.stock
            staffed.construction shouldBe city.construction
            CivsRules.autoAssign(staffed, city.owner, 400_001) shouldBe staffed
        }
    }

    test("critical reassignment preserves personal jobs and waits for durable or runtime work to finish") {
        val city = autonomyLostBuilder()
        val worker = city.workers.single()
        val root = city.center.copy(y = city.center.y + 1)
        val centre = (15 shl 5) or 15
        val tree = CivForestryJob(UUID(30, 1), root, "OAK_SAPLING", listOf(root),
            listOf("minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"),
            listOf((1 shl 16) or (1 shl 10) or centre, centre), next = 1)
        // Personal captured batches carry native materials rather than the Auto fixed stock price.
        val personal = city.copy(autonomy = null, construction = city.construction!!.copy(cost = Stock()))
        for (blocked in listOf(personal,
            city.copy(autonomy = city.autonomy!!.copy(paused = true)),
            city.copy(pendingDeletion = CivDeletion(null)),
            city.copy(forestryJobs = mapOf(worker.assignment.toString() to tree)))) {
            CivsRules.needsAutoAssignment(blocked) shouldBe false
            if (blocked.pendingDeletion == null) CivsRules.autoAssign(autonomyRoundtrip(blocked), blocked.owner, 300_000) shouldBe blocked
        }
        CivsRules.needsAutoAssignment(city) { false } shouldBe false
        CivsRules.autoAssign(city, city.owner, 300_000) { false } shouldBe city
        CivsRules.needsAutoAssignment(city) { it == worker.id } shouldBe true
        val child = worker.copy(assignment = null, nextDeliveryAt = 0, childMinutes = 0,
            parentIds = setOf(UUID(40, 1), UUID(40, 2)))
        CivsRules.needsAutoAssignment(city.copy(workers = listOf(child))) shouldBe false
        val carrying = worker.copy(cargo = CivCargo(UUID(40, 3), UUID(40, 4), UUID(40, 4), UUID(40, 5),
            root, "BREAD", 1))
        CivsAutonomy.criticalAssignment(city.copy(workers = listOf(carrying))) { true } shouldBe null
    }

    test("accepted production and forestry receipts sustain twelve buildings including the board and three houses without outside inputs") {
        var city = autonomyImmigrants(autonomyCity())
        var now = 300_000L
        val completed = mutableListOf<Pair<BuildingType, Int>>()
        val supplyAges = mutableMapOf<CivSupplyTask, Long>()
        var minutes = 0
        while (completed.size < 23 && CivsAutonomy.nextGoal(city) != null && minutes++ < 1_000) {
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
                    CivsAutonomousLogistics.room(city, resource) >= CivsRules.deliveryAmount(job)) city =
                    if (job.type == BuildingType.FORESTER) autonomyForestryReceipt(city, worker, now) else autonomyTrip(city, worker, now)
            }
            val sources = city.workers.filter { it.pendingStock != null }.mapNotNull { worker ->
                worker.assignment?.let(city::building)?.let { worker.id to it.entrance }
            }.toMap()
            val supply = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null }, sources) { false }
            val deliveries = allocateCivSupplies(supply.detached(), supplyAges, now = now)
            deliveries.seen.forEach { supplyAges.putIfAbsent(it, now) }
            deliveries.served.forEach(supplyAges::remove)
            for ((worker, plan) in deliveries.plans) {
                city = CivsLogistics.pickup(city, worker, plan.cargo)
                city = CivsLogistics.unload(city, worker, plan.cargo.id, now)
            }
            city = CivsPopulation.advance(city, city.workers.filter { it.assignment != null }.mapTo(hashSetOf()) { it.id }, CivsNames::name)
            city = CivsAutonomy.activeMinute(city)
        }
        (minutes < 1_000) shouldBe true
        completed.take(CivsAutonomy.baseProfile.size).map { it.first } shouldBe CivsAutonomy.baseProfile
        completed.size shouldBe 23
        CivsAutonomy.nextGoal(city)!!.type shouldBe BuildingType.UNIVERSITY
        CivsAutonomy.nextGoal(city)!!.targetLevel shouldBe 1
        city.buildings.size shouldBe 12
        city.buildings.all { it.level == minOf(2, it.type.maxLevel) && it.phase == BuildingPhase.ACTIVE } shouldBe true
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
    val x = if (type == BuildingType.PLAN_BOARD) -16 else type.ordinal * 8; val z = occurrence * 12
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

private fun autonomyLostBuilder(): Settlement {
    val hut = autonomyBuilding(BuildingType.BUILDER_HUT, phase = BuildingPhase.ACTIVE)
    val forest = autonomyBuilding(BuildingType.FORESTER, phase = BuildingPhase.ACTIVE)
    val quarry = autonomyBuilding(BuildingType.QUARRY, phase = BuildingPhase.ACTIVE)
    val farm = autonomyBuilding(BuildingType.FARM, phase = BuildingPhase.CONSTRUCTING)
    val base = autonomyImmigrants(autonomyCity())
    val city = CivsRules.validate(base.copy(buildings = base.buildings + listOf(hut, forest, quarry),
        workers = listOf(base.workers.single { it.id == UUID(2, 0) }.copy(assignment = forest.id,
            nextDeliveryAt = 300_000, health = 1.0)), stock = Stock(92, 56, 3),
        autonomy = CivAutonomy(recruitmentMinutes = CivsAutonomy.RECRUITMENT_MINUTES)))
    val reserved = CivsRules.reserveBuild(city, city.owner, farm, autonomyOrder(farm, 287))
    return CivsRules.validate(reserved.copy(construction = reserved.construction!!.copy(workDone = 26, blocksPlaced = 26)))
}

private fun autonomyOrder(building: CivBuilding, rows: Int) = CivConstruction(UUID.randomUUID(), building.id, building.level,
    rows, 0, ConstructionPhase.CONSTRUCTING, CivsAutonomy.cost(building.type, building.level), plannedBuilding = building,
    batches = CivConstructionBatches(rows, 16, true), materials = mapOf("STONE_BRICKS" to 999L, "BOOK" to 20L),
    blocksRequired = rows, blocksPlaced = 0)

private fun autonomyTrip(city: Settlement, worker: CivWorker, now: Long): Settlement = CivsRules.deliver(city, city.owner,
    worker.id, requireNotNull(worker.assignment), worker.completedCycles, worker.nextDeliveryAt, now)

/** Accepted domain observation only; native growth and route arrival are separate platform checks. */
private fun autonomyForestryReceipt(city: Settlement, worker: CivWorker, now: Long): Settlement {
    val building = requireNotNull(worker.assignment?.let(city::building))
    val root = city.center.copy(y = city.center.y + 1)
    val centre = (15 shl 5) or 15
    val job = CivForestryJob(UUID.randomUUID(), root, "OAK_SAPLING", listOf(root),
        listOf("minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"),
        listOf((1 shl 16) or (5 shl 10) or centre) + (4 downTo 0).map { (it shl 10) or centre },
        seedDeferred = city.stock.wood == 0L)
    val work = CivWorldWork(worker.id, worker.completedCycles, building.id, root.copy(x = root.x - 1),
        job.cells.map { code -> CivPlanBlock(job.position(code), job.state(code),
            if (job.position(code) == root) "minecraft:oak_sapling[stage=0]" else "minecraft:air") },
        inputs = if (job.seedDeferred) emptyMap() else mapOf("OAK_SAPLING" to 1L), status = "felling-tree", focus = root,
        forestry = CivForestryProgress(job, job.cells.size, harvestedLogs = 5))
    return CivsRules.finishWorldWork(city, work, now)
}

private fun autonomyRoundtrip(city: Settlement): Settlement = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))

private fun autonomyReject(reason: String, action: () -> Unit) {
    shouldThrow<CivsRejection>(action).reason shouldBe reason
}
