package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomousVisualReceiptTest : FunSpec({
    test("a native farm subaction changes only the city revision and conserves the economic session") {
        val (city, work) = visualReceiptFixture("farmer")
        val after = CivsRules.finishAutonomousVisual(city, work)
        after shouldBe city.copy(revision = city.revision + 1)
        val transaction = autonomousVisualTransaction(city, work)
        transaction.validated() shouldBe transaction
        transaction.autonomousVisual!!.before(after) shouldBe city
        // Validating/recovering the same receipt is read-only and cannot train or pay the worker twice.
        repeat(3) { transaction.validated().after shouldBe after }
        after.workers shouldBe city.workers
        after.stock shouldBe Stock(7, 8, 9)
        after.autonomy!!.products shouldBe city.autonomy!!.products
    }

    test("a native mine subaction advances its exact versioned cursor while preserving economic and legacy state") {
        val (initial, work) = visualReceiptFixture("miner")
        val mine = requireNotNull(initial.building(work.buildingId))
        val key = mine.id.toString()
        val previous = CivMinePlanState(4, 4, legacyStep = 3, v2Step = 2, v3Step = 1)
        val city = CivsRules.validate(initial.copy(mineProgress = mapOf(key to 3L), minePlans = mapOf(key to previous)))
        val transaction = autonomousVisualTransaction(city, work)
        val after = transaction.validated().after
        after shouldBe city.copy(revision = city.revision + 1,
            minePlans = mapOf(key to CivMinePlanState(CivsMinePlan.VERSION, requireNotNull(work.miningStep),
                legacyStep = 3, v2Step = 2, v3Step = 1, v4Step = 4)))
        transaction.autonomousVisual!!.before(after) shouldBe city
        after.workers.single().completedCycles shouldBe 7L
        after.workers.single().nextDeliveryAt shouldBe 60_000L
        after.workers.single().skills shouldBe mapOf("QUARRY" to 3L)
        after.workers.single().items shouldBe city.workers.single().items
        after.autonomy shouldBe city.autonomy
        after.stock shouldBe city.stock
        val savedPlan = requireNotNull(after.minePlans).getValue(key)
        shouldThrow<IllegalArgumentException> {
            transaction.copy(after = after.copy(minePlans = after.minePlans!! +
                (key to savedPlan.copy(nextStep = requireNotNull(work.miningStep) + 1)))).validated()
        }
    }

    test("a mine repair with an unchanged cursor still gets one native receipt revision") {
        val (initial, work) = visualReceiptFixture("miner")
        val key = work.buildingId.toString()
        val city = CivsRules.validate(initial.copy(minePlans = mapOf(key to CivMinePlanState(CivsMinePlan.VERSION, 0))))
        val repair = work.copy(miningStep = 0, status = "securing-mine")
        val transaction = autonomousVisualTransaction(city, repair)
        transaction.validated().after shouldBe city.copy(revision = city.revision + 1)
        transaction.autonomousVisual!!.before(transaction.after) shouldBe city
    }

    for (role in listOf("farmer", "miner"))
    test("a $role visual receipt survives its durable codec and rejects missing captured identity") {
        val (city, work) = visualReceiptFixture(role)
        val transaction = autonomousVisualTransaction(city, work).validated()
        val gson = Gson()
        val decoded = gson.fromJson(gson.toJson(transaction), CivInventoryTransaction::class.java).validated()
        decoded shouldBe transaction
        decoded.autonomousVisual!!.before(decoded.after) shouldBe city
        for (field in listOf("cityId", "worldId", "ownerId", "buildingBefore")) {
            val json = gson.toJsonTree(transaction).asJsonObject
            json.getAsJsonObject("autonomousVisual").remove(field)
            withClue(field) {
                shouldThrow<IllegalArgumentException> {
                    gson.fromJson(json, CivInventoryTransaction::class.java).validated()
                }
            }
        }
    }

    test("a visual receipt rejects forged stock products inventory and economic token without native output") {
        val (city, work) = visualReceiptFixture("farmer")
        val transaction = autonomousVisualTransaction(city, work)
        val after = transaction.after
        val worker = after.workers.single()
        val forgedWorkers = listOf(
            worker.copy(completedCycles = worker.completedCycles + 1),
            worker.copy(nextDeliveryAt = worker.nextDeliveryAt + 1),
            worker.copy(skills = worker.skills!! + ("FARM" to 4L)),
            worker.copy(items = worker.items!!.toMutableList().also { it[CivsEquipment.BAG] = CivResidentItem("DIAMOND", 11) }),
        )
        val forged = listOf(
            after.copy(stock = after.stock.copy(food = after.stock.food + 1)),
            after.copy(autonomy = after.autonomy!!.copy(products = mapOf(CivIndustryProduct.GLASS to 4L))),
        ) + forgedWorkers.map { after.copy(workers = listOf(it)) }
        for (candidate in forged) withClue(candidate) {
            shouldThrow<IllegalArgumentException> { transaction.copy(after = candidate).validated() }
        }
        val receipt = requireNotNull(transaction.autonomousVisual)
        for (bad in listOf(
            work.copy(inputs = mapOf("WHEAT_SEEDS" to 1L)),
            work.copy(outputs = mapOf("WHEAT" to 3L)),
            work.copy(equipmentAfter = city.workers.single().items),
            work.copy(cycle = work.cycle + 1),
            work.copy(autonomousVisual = false),
        )) withClue(bad) {
            shouldThrow<IllegalArgumentException> {
                transaction.copy(autonomousVisual = receipt.copy(work = bad)).validated()
            }
        }
        shouldThrow<IllegalArgumentException> {
            transaction.copy(autonomousVisual = receipt.copy(acceptedAt = -1)).validated()
        }
        shouldThrow<IllegalArgumentException> {
            transaction.copy(beforeRevision = transaction.after.revision).validated()
        }
    }

    test("captured city world owner and worksite bind the visual receipt after decoding") {
        val (city, work) = visualReceiptFixture("farmer")
        val transaction = autonomousVisualTransaction(city, work)
        val after = transaction.after
        val changedId = UUID.randomUUID()
        val building = requireNotNull(after.building(work.buildingId))
        val wrongBuilding = building.copy(entrance = building.entrance.copy(x = building.entrance.x + 1))
        val forged = listOf(
            transaction.copy(cityId = changedId, after = after.copy(id = changedId)),
            transaction.copy(after = after.copy(worldId = UUID.randomUUID())),
            transaction.copy(after = after.copy(owner = UUID.randomUUID())),
            transaction.copy(after = after.copy(buildings = after.buildings.map {
                if (it.id == building.id) wrongBuilding else it })),
        )
        for (candidate in forged) withClue(candidate.after) {
            shouldThrow<IllegalArgumentException> { candidate.validated() }
        }
    }

    test("native visual actions reject a foreign domain paused town physical store and ineligible worker") {
        val (city, work) = visualReceiptFixture("farmer")
        val worker = city.workers.single()
        for (bad in listOf(
            work.copy(changes = listOf(work.changes.single().copy(position = CivPosition(40, 64, 0)))),
            work.copy(changes = listOf(work.changes.single().copy(after = "minecraft:diamond_block"))),
            work.copy(buildingId = UUID.randomUUID()),
            work.copy(workerId = UUID.randomUUID()),
        )) withClue(bad) {
            shouldThrow<IllegalArgumentException> { CivsRules.finishAutonomousVisual(city, bad) }
        }
        val child = worker.copy(assignment = null, completedCycles = 0, nextDeliveryAt = 0,
            childMinutes = 0, parentIds = setOf(UUID.randomUUID(), UUID.randomUUID()), skills = null)
        for (badCity in listOf(
            city.copy(autonomy = city.autonomy!!.copy(paused = true)),
            city.copy(autonomy = null, stock = Stock(), inventory = CivInventory(CivStorageMode.VIRTUAL)),
            city.copy(workers = listOf(worker.copy(assignment = null))),
            city.copy(workers = listOf(child)),
        )) withClue(badCity) {
            shouldThrow<IllegalArgumentException> { CivsRules.finishAutonomousVisual(badCity, work) }
        }
    }

    test("only explicit bounded observations complete the sixty-second economic session") {
        val (city, work) = visualReceiptFixture("farmer")
        val worker = city.workers.single()
        val building = requireNotNull(city.building(work.buildingId))
        val token = CitizenToken(building.id, worker.completedCycles, worker.nextDeliveryAt, null)
        val session = CivAutonomousVisualSession(token, building, 60_000)
        session.token shouldBe token
        session.building shouldBe building
        session.workedMillis shouldBe 0L
        session.remainingMillis shouldBe 60_000L
        session.ready shouldBe false
        repeat(23) { session.observe(2_500); session.ready shouldBe false }
        session.observe(2_499)
        session.workedMillis shouldBe 59_999L
        session.ready shouldBe false
        for (millis in listOf(-1L, 2_501L, Long.MAX_VALUE)) {
            shouldThrow<IllegalArgumentException> { session.observe(millis) }
            session.workedMillis shouldBe 59_999L
        }
        session.observe(0)
        session.ready shouldBe false
        session.observe(1)
        session.ready shouldBe true
        session.remainingMillis shouldBe 0L
        session.observe(2_500)
        session.workedMillis shouldBe 60_000L
        city.workers.single() shouldBe worker
    }

    test("restart or a replacement token never inherits unfinished work or a due timestamp as observed time") {
        val (city, work) = visualReceiptFixture("miner")
        val building = requireNotNull(city.building(work.buildingId))
        val worker = city.workers.single()
        val token = CitizenToken(building.id, worker.completedCycles, 1, null)
        val retired = CivAutonomousVisualSession(token, building, 60_000)
        repeat(20) { retired.observe(2_500) }
        retired.workedMillis shouldBe 50_000L
        val replacements = listOf(
            CivAutonomousVisualSession(token, building, 60_000),
            CivAutonomousVisualSession(token.copy(completedCycles = token.completedCycles + 1), building, 60_000),
            CivAutonomousVisualSession(token.copy(nextDeliveryAt = Long.MAX_VALUE), building, 60_000),
            CivAutonomousVisualSession(token, building.copy(level = 2), 60_000),
        )
        for (session in replacements) {
            session.workedMillis shouldBe 0L
            session.ready shouldBe false
            session.required(60_000)
            session.workedMillis shouldBe 0L
        }
        for (millis in listOf(0L, -1L)) {
            shouldThrow<IllegalArgumentException> { retired.required(millis) }
            retired.workedMillis shouldBe 50_000L
            retired.requiredMillis shouldBe 60_000L
        }
    }
})

internal fun visualReceiptFixture(role: String): Pair<Settlement, CivWorldWork> {
    val building = spatialBuilding("medievaloak", role, 1, CivPosition(0, 63, 0))
    val worker = CivWorker(UUID.randomUUID(), "Рабочий", building.id, completedCycles = 7, nextDeliveryAt = 60_000,
        skills = mapOf(building.type.name to 3L), items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
            4 -> CivResidentItem(if (role == "farmer") "IRON_HOE" else "IRON_PICKAXE", 1)
            CivsEquipment.BAG -> CivResidentItem("COAL", 11)
            else -> null
        } })
    val city = CivsRules.validate(spatialCity(UUID.randomUUID(), listOf(building), worker).copy(revision = 17,
        inventory = null, stock = Stock(7, 8, 9),
        autonomy = CivAutonomy(products = mapOf(CivIndustryProduct.GLASS to 3L))))
    val work = if (role == "farmer") {
        val crop = CivsWorksiteLayouts.field(building).first().let { it.copy(y = it.y + 1) }
        CivWorldWork(worker.id, worker.completedCycles, building.id, building.entrance,
            listOf(CivPlanBlock(crop, "minecraft:air", "minecraft:wheat[age=0]")), durationMillis = 3_000,
            status = "working-field", autonomousVisual = true)
    } else {
        val plan = requireNotNull(CivsMinePlan.forBuilding(building))
        val step = plan.steps.first()
        CivWorldWork(worker.id, worker.completedCycles, building.id, building.entrance,
            listOf(CivPlanBlock(CivsWorksiteLayouts.point(building, step.point),
                if (step.after == null) "minecraft:stone" else "minecraft:air", step.after ?: "minecraft:air")),
            durationMillis = 2_000, status = "digging-mine", miningStep = 1,
            miningPlanVersion = plan.version, autonomousVisual = true)
    }
    return city to work
}

internal fun autonomousVisualTransaction(city: Settlement, work: CivWorldWork): CivInventoryTransaction {
    val receipt = CivAutonomousVisualReceipt(workerBefore = city.workers.single { it.id == work.workerId },
        stockBefore = city.stock, autonomyBefore = requireNotNull(city.autonomy),
        mineProgressBefore = city.mineProgress, minePlansBefore = city.minePlans, work = work, acceptedAt = 12_000,
        cityId = city.id, worldId = city.worldId, ownerId = city.owner, buildingBefore = requireNotNull(city.building(work.buildingId)))
    return CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, CivsRules.finishAutonomousVisual(city, work),
        emptyList(), worldChanges = work.changes, autonomousVisual = receipt)
}
