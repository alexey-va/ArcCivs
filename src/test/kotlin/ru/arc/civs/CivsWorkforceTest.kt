package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsWorkforceTest : FunSpec({
    test("completed workplaces grow with level while an unfinished hut starts with one builder") {
        val city = workforceCity()
        for (type in BuildingType.entries.filterNot { it.isResidence || it.isInfrastructure ||
                it == BuildingType.GUARD_TOWER || it == BuildingType.PLAN_BOARD }) {
            for (level in 1..3) {
                val building = workforceBuilding(1, type).copy(level = level)
                CivsRules.slots(city, building) shouldBe level
            }
        }
        for (type in listOf(BuildingType.HOUSE, BuildingType.TAVERN, BuildingType.WALL, BuildingType.GATE,
            BuildingType.ROAD, BuildingType.PLAN_BOARD))
            CivsRules.slots(city, workforceBuilding(1, type)) shouldBe 0
        for (level in 1..3) CivsRules.slots(city, workforceBuilding(1, BuildingType.GUARD_TOWER).copy(level = level)) shouldBe 1
        CivsRules.slots(workforceBuilding(2, BuildingType.BUILDER_HUT).copy(phase = BuildingPhase.SITE)) shouldBe 1
        CivsRules.slots(workforceBuilding(1, BuildingType.TOWN_HALL).copy(phase = BuildingPhase.SITE)) shouldBe 0
    }

    test("normal hall scribes require an item receipt and earn only their new profession experience") {
        val base = workforceCity()
        val hall = base.buildings.first()
        val worker = base.workers.first().copy(assignment = hall.id, completedCycles = 100,
            items = List(CivsEquipment.SLOTS) { when (it) {
                CivsEquipment.BAG -> CivResidentItem("PAPER", 3)
                CivsEquipment.BAG + 1 -> CivResidentItem("LEATHER", 1)
                else -> null
            } })
        val city = CivsRules.validate(base.copy(workers = listOf(worker)))
        CivsRules.effectiveWorkType(city, hall) shouldBe BuildingType.TOWN_HALL
        CivsRules.profession(city, hall) shouldBe "scribe"
        CivsRules.deliveryInterval(city, hall) shouldBe 60_000
        CivsProduction.options(hall.type) shouldBe listOf("BOOK")
        CivsProduction.inputs(hall) shouldBe mapOf("PAPER" to 3L, "LEATHER" to 1L)
        for (level in 1..3) CivsProduction.amount(hall.copy(level = level)) shouldBe 1L
        workforceDenied("materials-required") { workforceTrip(city, worker) }
        val after = CivsRules.deliver(city, city.owner, worker.id, hall.id,
            worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt, outputHandled = true)
        after.workers.single().skills shouldBe mapOf("TOWN_HALL" to 1L)
        after.workers.single().completedCycles shouldBe 101L
        after.workers.single().nextDeliveryAt shouldBe worker.nextDeliveryAt + 60_000
        after.inventory shouldBe city.inventory // Exact debit and book credit belong to the shared storage receipt.
        workforceDenied("stale-delivery") {
            CivsRules.deliver(after, after.owner, worker.id, hall.id, worker.completedCycles,
                worker.nextDeliveryAt, worker.nextDeliveryAt, outputHandled = true)
        }
        val experienced = worker.copy(skills = mapOf("TOWN_HALL" to 40L, "BUILDER_HUT" to 40L), inventory = mapOf("IRON_SHOVEL" to 1))
        CivsResidents.maxHealth(experienced) shouldBe 28.0
        CivsResidents.damage(experienced, CivsRules.effectiveWorkType(city, hall), 10.0) shouldBe 10.0
    }

    test("old paid and full cities keep one real hall builder without changing normal scribe slots") {
        val base = workforceCity()
        val hall = base.buildings.first()
        val old = base.copy(inventory = null, workers = emptyList())
        CivsRules.isBuilder(old, hall) shouldBe true
        CivsRules.slots(old, hall) shouldBe 1
        val full = CivsRules.validate(base.copy(buildings = listOf(hall) + (2..16).map {
            workforceBuilding(it, BuildingType.HOUSE)
        }, workers = emptyList()))
        CivsRules.legacyHallBuilder(full) shouldBe true
        CivsRules.effectiveWorkType(full, hall) shouldBe BuildingType.BUILDER_HUT
        CivsRules.slots(full, hall) shouldBe 1
        CivsRules.productionAvailable(full, hall) shouldBe false
        CivsRules.profession(full, hall) shouldBe "builder"
        CivsRules.slots(full.copy(buildings = full.buildings.dropLast(1)), hall) shouldBe 3
    }

    test("builders add observed credit while one row places without replacing each others work") {
        var city = workforceOrder(188)
        val (first, second, third) = city.workers
        val secondPlan = citizenPlan(city, second)
        val before = city
        city = workforceTrip(city, first)
        city.construction!!.workCredit shouldBe 64
        citizenPlan(city, second) shouldBe secondPlan!!.copy(idleReason = "placing")
        city = workforceTrip(city, second)
        city.construction!!.workCredit shouldBe 128
        city.construction!!.workDone shouldBe 0
        workforceDenied("stale-delivery") { workforceTrip(city, first) }
        val orderId = city.construction!!.id
        city = city.copy(workers = city.workers.mapIndexed { index, resident -> if (index == 0)
            resident.copy(items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("STONE", 1) else null })
            else resident })
        city = CivsRules.payBatch(city, city.owner, orderId, 0, mapOf("STONE" to 1L))
        val thirdPlan = citizenPlan(before, third)
        citizenPlan(city, third) shouldBe thirdPlan!!.copy(idleReason = "placing")
        city = workforceRoundTrip(workforceTrip(city, third))
        city.construction!!.phase shouldBe ConstructionPhase.PLACING
        city.construction!!.payment!!.reservedBatch shouldBe 0
        city.construction!!.workCredit shouldBe 188
        city.workers.map { it.completedCycles } shouldBe listOf(1L, 1L, 1L)
        workforceDenied("construction-credit-full") { workforceTrip(city, city.workers.first()) }
        city = CivsRules.hurtResident(city, city.owner, second.id, 100.0)
        city.construction!!.workCredit shouldBe 188
        city = CivsRules.completeBatch(workforceRoundTrip(city), city.owner, orderId)
        city.construction!!.workDone shouldBe 1
        city.construction!!.workCredit shouldBe 187
        city.construction!!.phase shouldBe ConstructionPhase.READY_TO_PLACE
    }

    test("builder credit remains bounded and paused or missing-material work earns nothing") {
        var city = workforceOrder(CivsRules.MAX_BUILDER_WORK_CREDIT + 1)
        repeat(CivsRules.MAX_POPULATION) { city = workforceTrip(city, city.workers.first()) }
        city.construction!!.workCredit shouldBe CivsRules.MAX_BUILDER_WORK_CREDIT
        workforceDenied("construction-credit-full") { workforceTrip(city, city.workers.first()) }
        val order = city.construction!!
        val waiting = CivsRules.awaitMaterials(city, city.owner, order.id, 0, mapOf("STONE" to 1L))
        workforceDenied("construction-not-working") { workforceTrip(waiting, waiting.workers.first()) }
        val paused = CivsRules.pauseConstruction(city, city.owner, order.id, "world-changed")
        workforceDenied("construction-not-working") { workforceTrip(paused, paused.workers.first()) }
        workforceRoundTrip(paused).construction!!.workCredit shouldBe CivsRules.MAX_BUILDER_WORK_CREDIT
    }

    test("automatic assignment fills builders and producers before the normal hall scribes") {
        val base = workforceCity()
        val hall = base.buildings.first().copy(level = 1)
        val hut = base.buildings[1].copy(level = 1)
        val forest = workforceBuilding(3, BuildingType.FORESTER)
        val city = CivsRules.validate(base.copy(buildings = listOf(hall, hut, forest),
            workers = base.workers.map { it.copy(assignment = null, nextDeliveryAt = 0) }))
        val assigned = CivsRules.autoAssign(city, city.owner, 1_000)
        assigned.workers.map { it.assignment } shouldBe listOf(hut.id, forest.id, hall.id)
        assigned.workers.map { it.nextDeliveryAt } shouldBe listOf(11_000L, 61_000L, 61_000L)
        CivsRules.autoAssign(assigned, assigned.owner, 2_000) shouldBe assigned
    }
})

private fun workforceId(value: Int) = UUID(22, value.toLong())
private fun workforceBuilding(key: Int, type: BuildingType): CivBuilding {
    val x = (key - 1) * 10
    return CivBuilding(workforceId(key), type, 1, CivPosition(x, 64, 2), 0, "test/${type.name.lowercase()}",
        CivBox(CivPosition(x - 1, 64, -1), CivPosition(x + 1, 68, 1)), CivPosition(x, 65, 2),
        BuildingPhase.ACTIVE, control = CivPosition(x, 64, 2))
}
private fun workforceCity(): Settlement {
    val hall = workforceBuilding(1, BuildingType.TOWN_HALL).copy(level = 3)
    val hut = workforceBuilding(2, BuildingType.BUILDER_HUT).copy(level = 3)
    return CivsRules.validate(Settlement(workforceId(50), workforceId(51), "Workforce", workforceId(52),
        CivPosition(0, 64, 0), 192, "test", Stock(), listOf(hall, hut),
        (100..102).map { CivWorker(workforceId(it), "Resident $it", hut.id, nextDeliveryAt = 2_000) },
        inventory = CivInventory(CivStorageMode.VIRTUAL)))
}
private fun workforceOrder(rows: Int): Settlement {
    val base = workforceCity()
    val site = workforceBuilding(3, BuildingType.FARM).copy(phase = BuildingPhase.SITE)
    val city = CivsRules.validate(base.copy(buildings = base.buildings + site))
    val order = CivConstruction(workforceId(500), site.id, 1, rows, 0, ConstructionPhase.CONSTRUCTING, Stock(),
        plannedBuilding = site.copy(phase = BuildingPhase.CONSTRUCTING), batches = CivConstructionBatches(rows, 16),
        materials = mapOf("STONE" to rows.toLong()), payment = CivConstructionPayment())
    return CivsRules.startSite(city, city.owner, site, order)
}
private fun workforceTrip(city: Settlement, worker: CivWorker): Settlement = CivsRules.deliver(city, city.owner,
    worker.id, requireNotNull(worker.assignment), worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt)
private fun workforceRoundTrip(city: Settlement): Settlement = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
private fun workforceDenied(reason: String, action: () -> Unit) { shouldThrow<CivsRejection>(action).reason shouldBe reason }
