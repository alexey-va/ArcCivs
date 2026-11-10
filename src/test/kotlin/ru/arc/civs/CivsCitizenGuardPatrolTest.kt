package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.util.UUID

class CivsCitizenGuardPatrolTest : FunSpec({
    for (paid in listOf(false, true)) test("Auto4 guard uses its retained tower approach only when paid=$paid") {
        val guardId = UUID.fromString("5cd4db03-2451-4fa7-9920-55e737dced8e")
        residentRuntime(workerId = guardId) { h ->
            val entrance = CivPosition(12582, 64, 10325)
            val approach = buildList {
                for (x in 12562..12564) add(CivPosition(x, 68, 10350))
                for (x in 12565..12577) add(CivPosition(x, 69, 10350))
                for (z in 10349 downTo 10348) add(CivPosition(12577, 69, z))
                for (z in 10347 downTo 10344) add(CivPosition(12577, 68, z))
                for (z in 10343 downTo 10341) add(CivPosition(12577, 67, z))
                for (z in 10340 downTo 10329) add(CivPosition(12577, 66, z))
                for (x in 12578..12580) add(CivPosition(x, 66, 10329))
                add(CivPosition(12580, 66, 10328))
                add(CivPosition(12580, 65, 10327))
                for (x in 12580..12582) add(CivPosition(x, 64, 10326))
                add(entrance)
            }
            val tower = CivBuilding(UUID.fromString("2bb6b23a-9ebf-34b8-953c-7c2828afade0"), BuildingType.GUARD_TOWER, 2,
                CivPosition(12582, 63, 10325), 180, "guardtower2", CivBox(CivPosition(12577, 62, 10313),
                    CivPosition(12587, 81, 10324)), entrance, BuildingPhase.ACTIVE, control = CivPosition(12581, 64, 10317))
            val gate = CivBuilding(UUID.fromString("2bcbf540-d238-31c5-adb5-57977f2af411"), BuildingType.GATE, 1,
                CivPosition(12563, 68, 10362), 0, "retained-gate", CivBox(CivPosition(12560, 68, 10362), CivPosition(12564, 73, 10366)),
                CivPosition(12563, 69, 10361), BuildingPhase.ACTIVE,
                transit = listOf(CivPosition(12560, 69, 10362), CivPosition(12562, 69, 10364), CivPosition(12564, 69, 10366)))
            val hall = h.city.buildings.first().copy(level = 2, anchor = CivPosition(12562, 67, 10350), entrance = approach.first(),
                bounds = CivBox(CivPosition(12550, 63, 10329), CivPosition(12575, 85, 10349)), control = CivPosition(12566, 68, 10340))
            h.city = h.city.copy(center = CivPosition(12563, 68, 10349), radius = 128, buildings = listOf(hall, tower, gate),
                workers = listOf(h.city.workers.single().copy(assignment = tower.id)), inventory = CivInventory(CivStorageMode.PHYSICAL))
            val journal = CivPlacementPlan(tower.id, h.city.id, h.city.worldId, tower.copy(phase = BuildingPhase.CONSTRUCTING),
                emptyList(), "a".repeat(64), CivTerraformPlan(CivBox(CivPosition(12562, 63, 10325), CivPosition(12582, 68, 10350)),
                    emptyList(), approach, 63, 0, 0))
            val access = CivAutonomousAccess.from(h.city, if (paid) listOf(journal) else emptyList())
            access.routes[tower.id] shouldBe if (paid) approach else null
            val roads = CivsPaths.RetainedRoads.from(h.city, access)
            val chunks = CitizenChunks.between(12545, 10310, 12599, 10367)
            for (x in chunks.minX..chunks.maxX) for (z in chunks.minZ..chunks.maxZ) h.world.loadChunk(x, z)
            for (feet in approach) h.world.getBlockAt(feet.x, feet.y - 1, feet.z).type = Material.DIRT_PATH
            h.world.time = 6000
            val start = entrance.location(h.world).add(0.0, -.0625, 0.0)
            h.paper.server.getPlayer("ResidentViewer")!!.teleport(start.clone().add(2.0, 0.0, 0.0))
            h.background = true
            h.recreateResidents()
            val frame = CivSettlementWorkFrame(h.city, CivResidentReadFrame(), CivSettlementSupplyRead.EMPTY, roads, CivRouteTerritory(h.city))
            h.owner.settlementFrames = { frame }
            h.owner.requestAdmissionAt(h.city.id, guardId, entrance.location(h.world))
            h.navigationNow = 1_500
            h.owner.pulse(1_500)
            withClue(h.owner.debug(h.city.id, guardId)) { h.owner.activeCount() shouldBe 1 }
            val actor = h.owner.collisionActors().single()
            actor.teleport(start)
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            for (now in listOf(3_000L, 9_000L, 15_000L)) {
                if (h.routeTarget() != null) break
                h.navigationNow = now
                h.owner.pulse(now)
            }
            if (paid) {
                val target = requireNotNull(h.routeTarget())
                val position = CivPosition(target.blockX, target.blockY, target.blockZ)
                (position in listOf(CivPosition(12578, 66, 10329), CivPosition(12577, 66, 10335))) shouldBe true
                for (tick in 1..250) {
                    val previous = actor.location
                    h.navigate()
                    (actor.location.distance(previous) <= .172001) shouldBe true
                    if (actor.location.distanceSquared(start) >= 4.0) break
                }
                (actor.location.distanceSquared(start) >= 4.0) shouldBe true
                (requireNotNull(h.routeProgress(guardId)) >= 1.5) shouldBe true
                h.owner.status(guardId) shouldBe "workers.status.patrolling"
                h.deliveries shouldBe 0
            } else {
                h.routeTarget() shouldBe null
                h.owner.status(guardId) shouldBe "workers.status.guarding"
            }
        }
    }
})
