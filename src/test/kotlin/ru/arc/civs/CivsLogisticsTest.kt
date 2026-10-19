package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsLogisticsTest : FunSpec({
    test("accepted cargo removes duplicate demand and a second courier chooses another item") {
        val city = logisticsCity()
        val warehouse = city.buildings[1]
        val smith = city.buildings[2]
        val stock: (UUID) -> Map<String, Long> = { if (it == warehouse.id) mapOf("IRON_INGOT" to 9L, "STICK" to 9L) else emptyMap() }
        val plan = CivsLogistics.plan(city, city.workers.first(), stock)!!
        plan.cargo.material shouldBe "IRON_INGOT"
        plan.cargo.amount shouldBe 3L
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        CivsLogistics.pickup(picked, picked.workers.first().id, plan.cargo) shouldBe picked
        val request = CivsLogistics.requests(picked, stock).single { it.buildingId == smith.id && it.material == "IRON_INGOT" }
        request.needed shouldBe 3L
        request.inTransit shouldBe 3L
        CivsLogistics.plan(picked, picked.workers[1], stock)!!.cargo.material shouldBe "STICK"
        CivsLogistics.plan(picked, picked.workers.first(), stock)!!.carrying shouldBe true
    }

    test("one completed shipment clears cargo and awards one warehouse point without replay") {
        val city = logisticsCity()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val restored = Gson().fromJson(Gson().toJson(picked), Settlement::class.java).let(CivsRules::validate)
        restored.workers.first().cargo shouldBe plan.cargo
        val delivered = CivsLogistics.unload(restored, restored.workers.first().id, plan.cargo.id, 10_000)
        delivered.workers.first().cargo shouldBe null
        delivered.workers.first().completedCycles shouldBe 1L
        delivered.workers.first().skills!!["WAREHOUSE"] shouldBe 1L
        shouldThrow<CivsRejection> { CivsLogistics.unload(delivered, delivered.workers.first().id, plan.cargo.id, 11_000) }.reason shouldBe "stale-delivery"
    }

    test("carried cargo survives reassignment and a deleted destination returns to a real depot") {
        val city = logisticsCity()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val redirected = picked.copy(buildings = picked.buildings.filter { it.id != plan.cargo.targetBuildingId },
            workers = picked.workers.map { if (it.id == picked.workers.first().id || it.assignment == plan.cargo.targetBuildingId) it.copy(assignment = null) else it })
            .let(CivsRules::validate)
        val returning = CivsLogistics.plan(redirected, redirected.workers.first()) { emptyMap() }!!
        returning.carrying shouldBe true
        returning.destinationBuildingId shouldBe city.buildings[1].id
        returning.cargo.sourceEntrance shouldBe city.buildings[1].entrance
    }

    test("deleted destinations skip unreachable warehouses and use a reachable hall or the nearest reachable warehouse") {
        val base = logisticsCity()
        val original = logisticsBuilding(80, BuildingType.WAREHOUSE, 2)
        val source = logisticsBuilding(70, BuildingType.FORESTER)
        val unreachable = logisticsBuilding(-70, BuildingType.WAREHOUSE)
        val city = base.copy(buildings = listOf(base.buildings.first(), original, base.buildings[2], source, unreachable),
            workers = base.workers.map { if (it.assignment == base.buildings[1].id) it.copy(assignment = original.id) else it })
            .let(CivsRules::validate)
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == source.id) mapOf("OAK_LOG" to 4L) else emptyMap() }!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val deleted = CivsRules.finishBuildingDeletion(CivsRules.requestDeletion(picked, picked.owner, original.id), picked.owner)
        val returning = CivsLogistics.plan(deleted, deleted.workers.first()) { emptyMap() }!!
        returning.carrying shouldBe true
        returning.destinationBuildingId shouldBe city.buildings.first().id
        returning.cargo shouldBe plan.cargo
        deleted.workers.first().cargo shouldBe plan.cargo
        val farther = logisticsBuilding(40, BuildingType.WAREHOUSE)
        val nearer = logisticsBuilding(60, BuildingType.WAREHOUSE)
        val alternatives = deleted.copy(buildings = deleted.buildings + listOf(farther, nearer)).let(CivsRules::validate)
        CivsLogistics.destination(alternatives, plan.cargo)?.id shouldBe nearer.id
    }

    test("continuous input requests and output backlogs share completed courier turns") {
        var city = logisticsCity()
        val warehouse = city.buildings[1]
        val smith = city.buildings[2]
        val forester = city.buildings[3]
        // Inputs are continually consumed and finished goods replenished between completed trips.
        val stock: (UUID) -> Map<String, Long> = { id -> when (id) {
            warehouse.id -> mapOf("IRON_INGOT" to 9L, "STICK" to 9L)
            smith.id -> mapOf("IRON_PICKAXE" to 4L)
            forester.id -> mapOf("OAK_LOG" to 100L)
            else -> emptyMap()
        } }
        val observed = mutableListOf<Triple<UUID, UUID, String>>()
        val amounts = mutableListOf<Long>()
        repeat(8) { index ->
            val worker = city.workers.first()
            val plan = CivsLogistics.plan(city, worker, stock)!!
            observed += Triple(plan.cargo.sourceBuildingId, plan.cargo.targetBuildingId, plan.cargo.material)
            amounts += plan.cargo.amount
            val picked = CivsLogistics.pickup(city, worker.id, plan.cargo)
            city = CivsLogistics.unload(picked, worker.id, plan.cargo.id, (index + 1) * 10_000L)
        }
        val round = listOf(Triple(warehouse.id, smith.id, "IRON_INGOT"), Triple(warehouse.id, smith.id, "STICK"),
            Triple(smith.id, warehouse.id, "IRON_PICKAXE"), Triple(forester.id, warehouse.id, "OAK_LOG"))
        observed shouldBe round + round
        amounts shouldBe listOf(3L, 2L, 4L, 64L, 3L, 2L, 4L, 64L)
        city.workers.first().completedCycles shouldBe 8L
        city.workers.first().skills!!["WAREHOUSE"] shouldBe 8L
    }

    test("eligible construction supply keeps priority over the ordinary rotation") {
        val base = logisticsCity()
        val site = logisticsBuilding(40, BuildingType.HOUSE).copy(phase = BuildingPhase.CONSTRUCTING)
        val required = mapOf("COBBLESTONE" to 1L)
        val order = CivConstruction(UUID.randomUUID(), site.id, 1, 1, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = site, batches = CivConstructionBatches(1, 16), materials = required,
            payment = CivConstructionPayment(awaiting = required))
        val city = base.copy(buildings = base.buildings + site, construction = order,
            workers = base.workers.mapIndexed { index, worker -> if (index == 0) worker.copy(completedCycles = 3) else worker })
            .let(CivsRules::validate)
        val warehouse = city.buildings[1]
        val stock: (UUID) -> Map<String, Long> = { id -> when (id) {
            warehouse.id -> mapOf("COBBLESTONE" to 1L, "IRON_INGOT" to 9L, "STICK" to 9L)
            city.buildings.last { it.type == BuildingType.FORESTER }.id -> mapOf("OAK_LOG" to 64L)
            else -> emptyMap()
        } }
        val plan = CivsLogistics.plan(city, city.workers.first(), stock)!!
        plan.cargo.targetBuildingId shouldBe site.id
        plan.cargo.material shouldBe "COBBLESTONE"
        plan.cargo.amount shouldBe 1L
    }

    test("death destroys carried goods and frees the original request without awarding a delivery") {
        val city = logisticsCity()
        val stock: (UUID) -> Map<String, Long> = { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }
        val plan = CivsLogistics.plan(city, city.workers.first(), stock)!!
        val picked = CivsLogistics.pickup(city, city.workers.first().id, plan.cargo)
        val dead = CivsRules.hurtResident(picked, picked.owner, picked.workers.first().id, 100.0)
        dead.workers.none { it.id == city.workers.first().id } shouldBe true
        CivsLogistics.requests(dead, stock).single { it.material == "IRON_INGOT" }.inTransit shouldBe 0L
        dead.workers.all { CivsResidents.skillExperience(it, "WAREHOUSE") == 0L } shouldBe true
    }

    test("available finished goods are collected when requested ingredients are absent") {
        val city = logisticsCity()
        val forester = city.buildings.last()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == forester.id) mapOf("OAK_LOG" to 100L) else emptyMap() }!!
        plan.cargo.sourceBuildingId shouldBe forester.id
        plan.cargo.targetBuildingId shouldBe city.buildings[1].id
        plan.cargo.amount shouldBe 64L
    }

    test("malformed or unbacked legacy cargo fails snapshot validation") {
        val city = logisticsCity()
        val plan = CivsLogistics.plan(city, city.workers.first()) { if (it == city.buildings[1].id) mapOf("IRON_INGOT" to 3L) else emptyMap() }!!
        CivsLogistics.validCargo(city, plan.cargo.copy(amount = 0)) shouldBe false
        CivsLogistics.validCargo(city.copy(inventory = null), plan.cargo) shouldBe false
        val tree = Gson().toJsonTree(plan.cargo).asJsonObject.also { it.remove("material") }
        CivsLogistics.validCargo(city, Gson().fromJson(tree, CivCargo::class.java)) shouldBe false
    }
})

private fun logisticsBuilding(x: Int, type: BuildingType, level: Int = 1) = CivBuilding(UUID.randomUUID(), type, level,
    CivPosition(x, 64, 0), 0, "test", CivBox(CivPosition(x - 2, 64, -2), CivPosition(x + 2, 68, 2)),
    CivPosition(x, 64, 3), BuildingPhase.ACTIVE, control = CivPosition(x, 63, 0),
    production = if (type == BuildingType.BLACKSMITH) "IRON_PICKAXE" else null)

private fun logisticsCity(): Settlement {
    val buildings = listOf(logisticsBuilding(0, BuildingType.TOWN_HALL, 2), logisticsBuilding(10, BuildingType.WAREHOUSE, 2),
        logisticsBuilding(20, BuildingType.BLACKSMITH), logisticsBuilding(30, BuildingType.FORESTER))
    return CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Cargo", UUID.randomUUID(),
        CivPosition(0, 64, 0), 96, "nordic", Stock(), buildings,
        listOf(CivWorker(UUID.randomUUID(), "Courier 1", buildings[1].id, nextDeliveryAt = 1),
            CivWorker(UUID.randomUUID(), "Courier 2", buildings[1].id, nextDeliveryAt = 1),
            CivWorker(UUID.randomUUID(), "Smith", buildings[2].id, nextDeliveryAt = 1)), inventory = CivInventory(CivStorageMode.VIRTUAL)))
}
