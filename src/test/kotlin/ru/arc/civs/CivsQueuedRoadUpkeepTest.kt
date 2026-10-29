package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

class CivsQueuedRoadUpkeepTest : FunSpec({
    test("an admitted paid road job survives a waiting queue without starting an unrelated second repair") {
        val city = roadAdmissionCity()
        val worker = city.workers.single()
        val network = roadAdmissionNetwork(city)
        val work = roadAdmissionWork(city, network.targets.single())
        val native = mockk<CivsWorld>()
        every { native.captureRoadUpkeep(any(), any(), any(), any()) } returns work
        every { native.roadUpkeepPending(any(), any()) } returns true
        var tick = 0
        val maintenance = CivsRoadMaintenance(native) { tick }
        maintenance.publish(city, network)
        maintenance.plan(city, worker) shouldBe work
        val queued = city.copy(constructionQueue = listOf(city.buildings.first().id))
        CivsRoadMaintenance.eligible(queued) shouldBe true
        tick++
        maintenance.plan(queued, worker) shouldBe work
        val after = CivsRoadMaintenance.complete(queued, work, 3_000)
        CivRoadUpkeepReceipt(worker, queued.stock, work, 3_000).validated(after)
        after.constructionQueue shouldBe queued.constructionQueue
        tick++
        maintenance.plan(after, after.workers.single()) shouldBe null
    }
})

internal fun roadAdmissionCity(): Settlement {
    val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(-40, 63, -40), 0, "fixture/hall",
        CivBox(CivPosition(-40, 63, -40), CivPosition(-38, 68, -38)), CivPosition(-39, 64, -37), BuildingPhase.ACTIVE)
    val hut = hall.copy(id = UUID(0, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(-40, 63, 30),
        bounds = CivBox(CivPosition(-40, 63, 30), CivPosition(-38, 68, 32)), entrance = CivPosition(-39, 64, 33))
    val road = hall.copy(id = UUID(0, 3), type = BuildingType.ROAD, anchor = CivPosition(0, 63, 0),
        bounds = CivBox(CivPosition(0, 63, 0), CivPosition(0, 65, 0)), entrance = CivPosition(0, 64, 0),
        transit = listOf(CivPosition(0, 64, 0)))
    val worker = CivWorker(UUID(0, 4), "Builder", hut.id, nextDeliveryAt = 1,
        items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 64) else null })
    return CivsRules.validate(Settlement(UUID(0, 5), UUID(0, 6), "Road admission", UUID(0, 7), CivPosition(0, 64, 0), 96,
        "caledonia", Stock(), listOf(hall, hut, road), listOf(worker),
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
}

internal fun roadAdmissionNetwork(city: Settlement): CivRoadUpkeepNetwork {
    val road = city.buildings.last()
    val floor = requireNotNull(road.transit).single().copy(y = 63)
    val plan = CivPlacementPlan(road.id, city.id, city.worldId, road,
        listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")))
    return CivsRoadMaintenance.prepare(city, listOf(plan))
}

internal fun roadAdmissionWork(city: Settlement, target: CivRoadUpkeep) = CivWorldWork(city.workers.single().id,
    city.workers.single().completedCycles, requireNotNull(city.workers.single().assignment),
    target.floor.copy(x = target.floor.x + 1, y = target.floor.y + 1),
    listOf(CivPlanBlock(target.floor, "minecraft:dirt", target.after)), inputs = mapOf("COBBLESTONE" to 1L),
    status = "repairing-road", focus = target.floor, upkeep = target)
