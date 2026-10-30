package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.util.UUID

class CivsQueuedRoadMaintenanceTest : FunSpec({
    test("a queued gate permits the grade repair which must retire before construction admission") {
        val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(-30, 63, -30), 0, "fixture/hall",
            CivBox(CivPosition(-30, 63, -30), CivPosition(-28, 68, -28)), CivPosition(-29, 64, -27), BuildingPhase.ACTIVE)
        val hut = hall.copy(id = UUID(0, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(-30, 63, 20),
            bounds = CivBox(CivPosition(-30, 63, 20), CivPosition(-28, 68, 22)), entrance = CivPosition(-29, 64, 23))
        val road = hall.copy(id = UUID(0, 3), type = BuildingType.ROAD, anchor = CivPosition(0, 63, 0),
            bounds = CivBox(CivPosition(0, 63, 0), CivPosition(0, 65, 0)), entrance = CivPosition(0, 64, 0),
            transit = listOf(CivPosition(0, 64, 0)))
        val gate = road.copy(id = UUID(0, 4), type = BuildingType.GATE, phase = BuildingPhase.SITE,
            anchor = CivPosition(20, 63, 0), entrance = CivPosition(20, 64, 0),
            bounds = CivBox(CivPosition(19, 63, 0), CivPosition(21, 68, 0)), transit = listOf(CivPosition(20, 64, 0)))
        val worker = CivWorker(UUID(0, 5), "Builder", hut.id, nextDeliveryAt = 1)
        val city = Settlement(UUID(0, 6), UUID(0, 7), "Queued grade", UUID(0, 8), CivPosition(0, 64, 0), 96,
            "caledonia", Stock(), listOf(hall, hut, road, gate), listOf(worker), autonomy = CivAutonomy(),
            inventory = CivInventory(CivStorageMode.PHYSICAL), constructionQueue = listOf(gate.id))
        val floor = road.entrance.copy(y = 63)
        val plan = CivPlacementPlan(road.id, city.id, city.worldId, road,
            listOf(CivPlanBlock(floor, "minecraft:dirt", "minecraft:cobblestone")))
        val upkeep = CivsRoadMaintenance.prepare(city, listOf(plan)).targets.single()
        val work = CivWorldWork(worker.id, 0, hut.id, floor.copy(x = 1, y = 64), plan.blocks,
            status = "repairing-road", focus = floor, upkeep = upkeep)
        var selected: CivWorldWork? = work
        val planning = CivsCitizenPlanning(CitizenRuntime(mockk()) { listOf(city) },
            { mockk() }, { mockk() }, { mockk() }, { mockk() }, { mockk() }, { mockk() }, { mockk() },
            { _, _ -> null }, { _, _ -> selected }, { _, _ -> "searching-work" },
            { _, _ -> true }, { _, _ -> true }, { _, _ -> null }, null, { _, _ -> })

        citizenPlan(city, worker)!!.idleReason shouldBe "queued-construction"
        val repair = requireNotNull(planning.plan(city, worker))
        repair.worldWork shouldBe work
        repair.idleReason shouldBe null
        repair.completeAtWorkplace shouldBe true
        repair.token.constructionId shouldBe null
        city.constructionQueue shouldBe listOf(gate.id)

        selected = null
        planning.plan(city, worker)!!.idleReason shouldBe "queued-construction"
        planning.plan(city.copy(autonomy = CivAutonomy(paused = true)), worker)!!.worldWork shouldBe null
    }
})
