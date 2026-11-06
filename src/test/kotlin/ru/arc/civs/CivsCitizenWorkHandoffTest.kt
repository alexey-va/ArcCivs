package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsCitizenWorkHandoffTest : FunSpec({
    for (type in listOf(BuildingType.QUARRY, BuildingType.FORESTER))
    for (discoveryGap in listOf(false, true))
    test("a prepared neighboring action starts on the fast handoff only after its exact receipt type=$type gap=$discoveryGap") {
        residentRuntime { h ->
            val site = h.assignHandoffWorksite(type)
            val material = if (type == BuildingType.QUARRY) Material.STONE else Material.OAK_LOG
            val status = if (type == BuildingType.QUARRY) "digging-mine" else "felling-tree"
            fun action(cycle: Long, standing: CivPosition, target: CivPosition): CivWorldWork {
                h.world.getBlockAt(target.x, target.y, target.z).type = material
                val before = h.world.getBlockAt(target.x, target.y, target.z).blockData.asString
                return CivWorldWork(h.workerId, cycle, site.id, standing,
                    listOf(CivPlanBlock(target, before, "minecraft:air")), durationMillis = 1_000,
                    status = status, focus = target)
            }
            val first = action(0, CivPosition(21, 64, 0), CivPosition(23, 64, 0))
            h.work = first
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            h.onDelivery = { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, first) }
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.WORKING
            h.owner.pulse(3_000)
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.deliveries shouldBe 1
            h.observedWork() shouldBe 1_000L

            // Publication can precede the old journal's durable completion. The next prepared
            // target must not replace that accepted receipt or receive any of its work time.
            val second = action(1, CivPosition(22, 64, 1), CivPosition(23, 64, 1))
            h.world.getBlockAt(23, 64, 0).type = Material.AIR
            h.city = h.city.copy(revision = h.city.revision + 1,
                workers = h.city.workers.map { it.copy(completedCycles = it.completedCycles + 1) })
            h.work = second.takeUnless { discoveryGap }
            h.idleReason = "searching-work"
            h.holdNavigation = true
            val plansBeforeReceipt = h.workPlans
            val feet = npc.location
            val captures = h.world.chunkSnapshotCaptures
            for (now in listOf(3_100L, 3_200L)) {
                h.owner.pulseFieldWork(now)
                h.phase() shouldBe CitizenPhase.DELIVERING
                h.actor().plan.worldWork shouldBe first
                h.workPlans shouldBe plansBeforeReceipt
                h.deliveries shouldBe 1
                h.observedWork() shouldBe 1_000L
                npc.location shouldBe feet
            }
            h.pendingDelivery.isDone shouldBe false
            h.pendingDelivery.complete(h.city) shouldBe true

            // No normal resident pulse occurs after 3000. Existing arrival/reach proof accepts
            // this neighboring target from the current supported feet without a new route.
            h.owner.pulseFieldWork(3_250)
            val startedAt = if (discoveryGap) {
                h.actor().plan.worldWork shouldBe null
                h.actor().plan.idleReason shouldBe "searching-work"
                h.phase() shouldBe CitizenPhase.IDLE
                h.observedWork() shouldBe 0L
                h.deliveries shouldBe 1
                h.work = second
                h.owner.pulseFieldWork(3_500)
                3_500L
            } else 3_250L
            h.actor().plan.worldWork shouldBe second
            h.phase() shouldBe CitizenPhase.WORKING
            h.observedWork() shouldBe 0L
            h.routeIdentity() shouldBe null
            h.pendingNavigationWorkers shouldBe 0
            h.world.chunkSnapshotCaptures shouldBe captures
            npc.location shouldBe feet
            h.deliveries shouldBe 1
            h.owner.pulseFieldWork(startedAt + 250)
            h.observedWork() shouldBe 250L
            h.deliveries shouldBe 1
            h.city.workers.single().completedCycles shouldBe 1L
        }
    }

    test("nine admitted foresters rotate through eight fast arrival slots without spawning or new route captures") {
        residentRuntime { h ->
            val site = h.assignHandoffWorksite(BuildingType.FORESTER)
            val ids = listOf(h.workerId) + (100L..107L).map { UUID(0, it) }
            h.city = h.city.copy(workers = ids.map { CivWorker(it, "Forester", site.id, nextDeliveryAt = 1) })
            h.idleReason = "searching-work"
            var prepared = emptyMap<UUID, CivWorldWork>()
            h.workProvider = { _, worker -> prepared[worker.id] }
            repeat(6) { h.owner.pulse(2_000L + it * 1_000) }
            val npcs = h.owner.collisionActors().associateBy { it.uniqueId }
            npcs.keys shouldBe ids.toSet()
            ids.forEach { h.phase(it) shouldBe CitizenPhase.IDLE }
            val offered = ids.mapIndexed { index, id ->
                val standing = CivPosition(21 + index % 3 * 3, 64, 2 + index / 3 * 3)
                val target = standing.copy(x = standing.x + 2)
                h.world.getBlockAt(target.x, target.y, target.z).type = Material.OAK_LOG
                // The ordinary pulse admits each job while these existing actors are still out of reach.
                npcs.getValue(id).teleport(Location(h.world, 8.5 + index % 3 * 2, 64.0, 2.5 + index / 3 * 2))
                id to CivWorldWork(id, 0, site.id, standing,
                    listOf(CivPlanBlock(target, h.world.getBlockAt(target.x, target.y, target.z).blockData.asString,
                        "minecraft:air")), durationMillis = 1_000, status = "felling-tree", focus = target)
            }.toMap()
            val spawns = h.spawns
            h.holdNavigation = true
            prepared = offered
            h.owner.pulse(8_000)
            ids.forEach { id ->
                h.actor(id).plan.worldWork shouldBe offered.getValue(id)
                h.phase(id) shouldBe CitizenPhase.TO_WORK
                npcs.getValue(id).teleport(offered.getValue(id).standing.location(h.world))
            }
            val captures = h.world.chunkSnapshotCaptures
            val pendingRoutes = h.pendingNavigationWorkers
            h.owner.pulseFieldWork(8_250)
            ids.count { h.phase(it) == CitizenPhase.WORKING } shouldBe 8
            ids.count { h.phase(it) == CitizenPhase.TO_WORK } shouldBe 1
            h.owner.pulseFieldWork(8_500)
            ids.forEach {
                h.phase(it) shouldBe CitizenPhase.WORKING
                h.actor(it).plan.worldWork shouldBe offered.getValue(it)
                h.routeIdentity(it) shouldBe null
            }
            h.spawns shouldBe spawns
            h.deliveries shouldBe 0
            h.pendingNavigationWorkers shouldBe pendingRoutes
            h.world.chunkSnapshotCaptures shouldBe captures
        }
    }
})

private fun ResidentRuntime.assignHandoffWorksite(type: BuildingType): CivBuilding {
    val site = assignFarm().copy(type = type, schematicId = if (type == BuildingType.QUARRY) "miner1" else "forester1",
        production = if (type == BuildingType.QUARRY) "COBBLESTONE" else "OAK_LOG")
    city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == site.id) site else it }))
    return site
}
