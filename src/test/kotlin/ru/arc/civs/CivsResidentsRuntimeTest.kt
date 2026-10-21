package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.ChunkMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

class CivsResidentsRuntimeTest : FunSpec({
    test("an accepted packet hit freezes work until durable injury and viewer retirement cannot heal it") {
        residentRuntime { h ->
            h.hit(7.0)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.hit(7.0)
            h.hits shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.visualHealth shouldBe 13.0
            h.viewing = false; h.owner.pulse(3_000)
            h.owner.activeCount() shouldBe 0
            h.viewing = true; h.owner.pulse(4_000)
            h.owner.activeCount() shouldBe 1
            h.visualHealth shouldBe 13.0
        }
    }

    test("durable death removes the worker and never creates a replacement packet actor") {
        residentRuntime { h ->
            h.hit(20.0)
            h.owner.activeCount() shouldBe 1
            h.commitHit(); h.owner.pulse(2_000)
            h.owner.activeCount() shouldBe 0
            repeat(5) { h.owner.pulse(3_000L + it * 1_000) }
            h.spawns shouldBe 1
            h.city.workers shouldBe emptyList()
        }
    }

    test("unknown injury commit stays frozen until explicit canonical reconciliation") {
        residentRuntime { h ->
            h.hit(3.0)
            h.pending.completeExceptionally(IllegalStateException("unknown commit"))
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.injury-pending"
            h.visualHealth shouldBe 20.0
            h.owner.reconcileResidents()
            h.owner.pulse(3_000)
            h.visualHealth shouldBe 20.0
        }
    }

    test("a miner waiting for a tool walks from the hall to its quarry through an upward ingress step") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 66.0, .5))
            h.world.getBlockAt(0, 65, 0).type = Material.STONE
            h.world.getBlockAt(1, 66, 0).type = Material.STONE
            h.world.getBlockAt(2, 65, 0).type = Material.STONE
            h.world.getBlockAt(3, 64, 0).type = Material.STONE
            h.owner.pulse(2_000)
            val control = requireNotNull(quarry.control)
            val destination = Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)
            // Drain the real 1ms search budget without advancing the recovery timeout.
            // Cold mock block reads may need more calls, but every movement must stay continuous.
            for (index in 0 until 2_000) {
                val before = actor.location
                h.owner.activateNavigating()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L)
                (before.distance(actor.location) <= .215001) shouldBe true
                if (actor.location.distanceSquared(destination) <= 8.0 * 8.0) break
            }
            val at = actor.location
            (at.distanceSquared(destination) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("an unreachable waiting miner recovers beside its quarry without completing work or returning to the hall") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.idleReason = "waiting-tool"
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, .5, 64.0, .5))
            for (x in -1..1) for (z in -1..1) if (x != 0 || z != 0)
                for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            h.owner.pulse(2_000)
            var recovered = false
            repeat(80) { index ->
                val before = actor.location
                h.owner.activateNavigating()
                if ((index + 1) % 20 == 0) h.owner.pulse(2_000L + (index + 1) * 50)
                if (before.distance(actor.location) > 8.0) recovered = true
            }
            val control = requireNotNull(quarry.control)
            val at = actor.location
            recovered shouldBe true
            (at.distanceSquared(Location(h.world, control.x + .5, control.y.toDouble(), control.z + .5)) <= 8.0 * 8.0) shouldBe true
            (at.blockX != control.x || at.blockZ != control.z) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-tool"
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.pendingDelivery.isDone shouldBe false
        }
    }

    test("an enclosed builder waiting for materials recovers into its home at night without work credit") {
        residentRuntime { h ->
            val hut = h.assignFarm().copy(type = BuildingType.BUILDER_HUT, schematicId = "builder1", production = null)
            val target = hut.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, phase = BuildingPhase.CONSTRUCTING,
                entrance = CivPosition(30, 64, 0), control = CivPosition(30, 64, 0), anchor = CivPosition(30, 64, 0),
                bounds = CivBox(CivPosition(28, 64, -2), CivPosition(32, 68, 2)))
            val home = h.city.buildings.first()
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(2, 16), materials = mapOf("STONE" to 1L),
                payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1L)), workCredit = 0)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(home, hut, target), construction = order,
                workers = h.city.workers.map { it.copy(homeId = home.id) }))
            h.world.time = 13_000
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 20.5, 68.0, .5))
            h.world.getBlockAt(20, 67, 0).type = Material.STONE
            for (x in 19..21) for (z in -1..1) if (x != 20 || z != 0)
                for (y in 68..70) h.world.getBlockAt(x, y, z).type = Material.STONE
            // A player occupying every recovery destination must keep the same failed route pending.
            h.owner.residentStepAllowed = { _, from, to -> from.distanceSquared(to) > .000001 }
            h.owner.pulse(2_000)
            repeat(200) { h.owner.activateNavigating() }
            h.owner.pulse(13_001)
            actor.location.x shouldBe 20.5
            h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
            h.owner.residentStepAllowed = { _, _, _ -> true }
            h.owner.pulse(14_001)
            val recovered = actor.location
            home.bounds.contains(recovered.blockX, recovered.blockY, recovered.blockZ) shouldBe true
            h.owner.pulse(15_001)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.status(h.workerId) shouldBe "workers.status.sleeping"
            h.city.construction shouldBe order
            h.city.workers.single().completedCycles shouldBe 0
            h.deliveries shouldBe 0
            h.pendingDelivery.isDone shouldBe false
            h.steps shouldBe emptyList()
            h.spawns shouldBe 1
        }
    }

    test("a courier unloads once then walks back to its warehouse and waits beside the real barrel") {
        residentRuntime { h ->
            val warehouse = h.assignFarm().copy(type = BuildingType.WAREHOUSE, schematicId = "warehouse1", production = null)
            val receiver = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.SAWMILL, schematicId = "sawmill1",
                anchor = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, 0), control = CivPosition(8, 64, 0),
                bounds = CivBox(CivPosition(6, 64, -2), CivPosition(10, 68, 2)))
            h.world.getBlockAt(8, 64, 0).type = Material.BARREL
            val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, receiver.id, warehouse.entrance, "OAK_LOG", 8)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(h.city.buildings.first(), warehouse, receiver),
                workers = h.city.workers.map { it.copy(cargo = cargo) }))
            h.courier = CivCourierPlan(cargo, receiver.id, receiver.entrance, true)
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 9.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.owner.pulse(3_000)
            h.unloads shouldBe 1
            h.city.workers.single().cargo shouldBe cargo
            h.city = h.city.copy(revision = h.city.revision + 1,
                workers = h.city.workers.map { it.copy(cargo = null, completedCycles = 1) })
            h.courier = null
            h.pendingUnload.complete(h.city)
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.moving-to-storage"
            for (tick in 1..2_000) {
                val before = actor.location
                h.owner.activateNavigating()
                if (tick % 20 == 0) h.owner.pulse(4_000)
                (before.distance(actor.location) <= .215001) shouldBe true
                if (h.owner.status(h.workerId) == "workers.status.waiting-orders") break
            }
            val atWarehouse = actor.location
            citizenAtStorage(h.world, CitizenPoint(atWarehouse.x, atWarehouse.y, atWarehouse.z), requireNotNull(warehouse.control)) shouldBe true
            citizenCanReachBlock(actor, requireNotNull(warehouse.control)) shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.waiting-orders"
            h.steps.isNotEmpty() shouldBe true
            repeat(80) { tick ->
                h.owner.activateNavigating()
                if (tick % 20 == 0) h.owner.pulse(5_000L + tick * 50)
                actor.location shouldBe atWarehouse
            }
            h.unloads shouldBe 1
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 1
            h.city.workers.single().cargo shouldBe null
            h.spawns shouldBe 1
        }
    }

    test("summoned farmer starts a supplied work plan at the barrel and keeps its delivery receipt fenced") {
        residentRuntime { h ->
            val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
                "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)),
                CivPosition(20, 64, 0), BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
            for (z in -1..1) h.world.loadChunk(2, z) // Loaded search margin east of the farm's chunk.
            h.world.getBlockAt(20, 64, 0).type = Material.BARREL
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + farm,
                workers = h.city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
                inventory = CivInventory(CivStorageMode.PHYSICAL)))
            h.owner.pulse(2_000) // A waiting farmer may already have an idle route from the former position.
            h.owner.activeCount() shouldBe 1
            val actor = h.owner.collisionActors().single()
            val previous = actor.location
            h.owner.summon(h.city, farm.id) shouldBe true
            val summoned = actor.location
            (previous.distance(summoned) > 8.0) shouldBe true
            h.deliveries shouldBe 0

            // Publication of supplied seeds makes a new field plan available on the next pulse.
            h.city = h.city.copy(revision = h.city.revision + 1,
                inventory = requireNotNull(h.city.inventory).copy(buildingMaterials = mapOf(farm.id.toString() to mapOf("WHEAT_SEEDS" to 1L))))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")), focus = focus,
                inputs = mapOf("WHEAT_SEEDS" to 1L), durationMillis = 1_500, status = "working-field")
            h.owner.pulse(3_000)
            actor.location shouldBe summoned
            h.spawns shouldBe 1
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.owner.activateNavigating()
                if (tick % 20 == 0) h.owner.pulse(3_000L + tick * 50)
                // A fresh plan walks from the summon, without a stale route or recovery jump.
                (before.distance(actor.location) <= .215001) shouldBe true
                (actor.location.x >= summoned.x) shouldBe true
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            (actor.location.x > summoned.x) shouldBe true
            h.spawns shouldBe 1
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            h.city.workers.single().completedCycles shouldBe 0
            h.city.inventory?.buildingStock(farm.id) shouldBe mapOf("WHEAT_SEEDS" to 1L)
            h.pendingDelivery.isDone shouldBe false
            h.owner.summon(h.city, farm.id) shouldBe false // Never abandon an admitted receipt.
            h.owner.pulse(12_000)
            h.deliveries shouldBe 1
        }
    }

    test("reachable work stays at the observed position faces the real block and rechecks LOS at receipt admission") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val observed = Location(h.world, 22.5, 64.0, .5)
            actor.teleport(observed)
            val focus = CivPosition(23, 63, 0)
            val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.work = work
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working-field"
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            repeat(20) { h.owner.activateNavigating() }
            actor.location shouldBe observed
            verify(atLeast = 1) { actor.swingMainHand() }
            verify(atLeast = 1) { actor.pitch = match { it > 45f } }
            h.owner.pulse(3_499)
            h.deliveries shouldBe 0
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            actor.location shouldBe observed
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)

            // A city wait must not let an already admitted action work through a newly placed wall.
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.world.getBlockAt(23, 64, 0).type = Material.AIR
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
            actor.teleport(Location(h.world, 27.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work) }
            h.pendingDelivery.isDone shouldBe false
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("field care stays at its crop and emits one contact cue only after its durable receipt") {
        for ((status, prop) in listOf("watering" to Material.WATER_BUCKET, "fertilizing" to Material.BONE_MEAL)) {
            residentRuntime { h ->
                val work = h.beginCare(status)
                val actor = h.owner.collisionActors().single()
                val observed = actor.location
                val items = h.city.workers.single().items
                h.displayedHand shouldBe prop
                h.owner.status(h.workerId) shouldBe "workers.status.$status"
                verify(atLeast = 1) { actor.swingMainHand() }
                verify(atLeast = 1) { actor.pitch = match { it > 0f } }
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
                // The cycle counts observed work, never a single four-second gap between pulses.
                for (at in 3_000L..5_000L step 1_000L) {
                    h.owner.pulse(at)
                    h.deliveries shouldBe 0
                }
                h.owner.pulse(6_000)
                h.deliveries shouldBe 1
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                actor.location shouldBe observed
                h.owner.pulse(7_000) // A delayed receipt must not masquerade as a completed watering.
                h.city.workers.single().items shouldBe items
                h.displayedHand shouldBe prop
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
                h.commitWork()
                h.owner.pulse(8_000)
                h.displayedHand shouldBe Material.IRON_HOE
                h.world.particles.single().let { (particle, count, at) ->
                    particle shouldBe if (status == "watering") Particle.SPLASH else Particle.HAPPY_VILLAGER
                    count shouldBe if (status == "watering") 8 else 6
                    at.x shouldBe requireNotNull(work.focus).x + .5
                    at.z shouldBe requireNotNull(work.focus).z + .5
                    (at.y > requireNotNull(work.focus).y && at.y < requireNotNull(work.focus).y + .5) shouldBe true
                }
                h.world.sounds.single() shouldBe Triple(
                    if (status == "watering") Sound.ITEM_BUCKET_EMPTY else Sound.ITEM_BONE_MEAL_USE, .35f, 1.1f)
                repeat(3) { h.owner.pulse(9_000L + it * 1_000) }
                h.world.particles.size shouldBe 1
                h.world.sounds.size shouldBe 1
            }
        }
    }

    test("failed injured and retired care restores the true hand without a success cue") {
        for (outcome in listOf("rejected", "injured", "retired")) {
            residentRuntime { h ->
                h.beginCare("watering")
                for (at in 3_000L..6_000L step 1_000L) h.owner.pulse(at)
                h.deliveries shouldBe 1
                h.displayedHand shouldBe Material.WATER_BUCKET
                when (outcome) {
                    "rejected" -> h.pendingDelivery.completeExceptionally(CivsRejection("stale-worksite"))
                    "injured" -> { h.hit(2.0); h.commitWork() }
                    else -> { h.viewing = false; h.owner.pulse(6_500); h.commitWork() }
                }
                h.owner.pulse(7_000)
                h.displayedHand shouldBe Material.IRON_HOE
                h.world.particles shouldBe emptyList()
                h.world.sounds shouldBe emptyList()
            }
        }
    }

    test("an out-of-reach worker approaches only until the actual block becomes reachable") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 21.5, 64.0, .5))
            val focus = CivPosition(25, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.deliveries shouldBe 0
            for (tick in 1..160) {
                val before = actor.location
                h.owner.activateNavigating()
                (before.distance(actor.location) <= .215001) shouldBe true
                if (tick % 20 == 0) h.owner.pulse(2_000L + tick * 50)
                if (h.deliveries != 0) break
            }
            h.deliveries shouldBe 1
            citizenCanReachBlock(actor, focus) shouldBe true
            (actor.location.x > 21.5 && actor.location.x < 24.0) shouldBe true
            (actor.location.distance(Location(h.world, 24.5, 64.0, .5)) > .65) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
        }
    }

    test("a worker already at another side of its barrel does not route to the preferred side") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            val otherSide = Location(h.world, 19.5, 64.0, .5)
            actor.teleport(otherSide)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            repeat(20) { h.owner.activateNavigating() }
            actor.location shouldBe otherSide
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location shouldBe otherSide
        }
    }

    test("storage approach stops at the first usable side between coarse work pulses") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 1_500, status = "equipping-tool")
            h.owner.pulse(2_000)
            val control = requireNotNull(farm.control)
            // The shared 1 ms search slice has no fixed operations-per-callback guarantee.
            // Hold coarse time still and observe the first usable side, without timeout recovery.
            for (tick in 1..2_000) {
                val before = actor.location
                h.owner.activateNavigating()
                val at = actor.location
                (before.distance(at) <= .215001) shouldBe true
                if (citizenAtStorage(h.world, CitizenPoint(at.x, at.y, at.z), control) &&
                    citizenCanReachBlock(actor, control)) break
            }
            val arrival = actor.location
            withClue("arrival=$arrival status=${h.owner.status(h.workerId)}") {
                citizenAtStorage(h.world, CitizenPoint(arrival.x, arrival.y, arrival.z), control) shouldBe true
                citizenCanReachBlock(actor, control) shouldBe true
            }
            (arrival.x < 20.0) shouldBe true
            repeat(40) { h.owner.activateNavigating() }
            actor.location shouldBe arrival
            h.deliveries shouldBe 0
            h.owner.pulse(3_000)
            h.owner.pulse(4_500)
            h.deliveries shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, farm.id)
            actor.location shouldBe arrival
        }
    }

    test("physical nudges preserve a pending receipt and stop at native walls") {
        residentRuntime { h ->
            val farm = h.assignFarm()
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 22.5, 64.0, .5))
            val focus = CivPosition(23, 63, 0)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(24, 64, 0),
                listOf(CivPlanBlock(focus, "minecraft:stone", "minecraft:air")),
                durationMillis = 1_500, status = "working-field", focus = focus)
            h.owner.pulse(2_000)
            h.owner.pulse(3_500)
            h.deliveries shouldBe 1
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe true
            h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
            h.owner.pulse(4_000)
            h.deliveries shouldBe 1
            h.pendingDelivery.isDone shouldBe false
            actor.teleport(Location(h.world, 22.7, 64.0, .5))
            h.world.getBlockAt(23, 64, 0).type = Material.STONE
            h.owner.nudgeResident(actor, Vector(.05, 0.0, 0.0)) shouldBe false
            actor.location.x shouldBe 22.7
        }
    }
})

private fun residentRuntime(block: (ResidentRuntime) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val fixture = ResidentRuntime(paper)
            try { block(fixture) } finally { fixture.owner.closeAsync().join() }
        }
    }
}

private class ResidentRuntime(paper: MockBukkitTestRuntime) {
    private val plugin = paper.createSimplePlugin("ResidentsTest")
    val world = ResidentRuntimeWorld().also(paper.server::addWorld)
    private val packets = mockk<PaperPacketNpcs>(relaxed = true)
    val workerId = UUID.randomUUID()
    var viewing = true
    var spawns = 0
    var hits = 0
    var deliveries = 0
    var unloads = 0
    var courier: CivCourierPlan? = null
    val steps = mutableListOf<Pair<Location, Location>>()
    var displayedHand = Material.AIR
    var work: CivWorldWork? = null
    var idleReason = "waiting-inputs"
    val pendingDelivery = CompletableFuture<Settlement>()
    val pendingUnload = CompletableFuture<Settlement>()
    val pending = CompletableFuture<Settlement>()
    private var damage = 0.0
    var city: Settlement
    val owner: CivsCitizens
    val visualHealth: Double get() = owner.hologramSnapshots().single().health

    init {
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        val viewer = paper.addPlayer("ResidentViewer")
        viewer.teleport(Location(world, .5, 64.0, .5))
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
            "townhall1", CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
        city = CivsRules.validate(Settlement(UUID.randomUUID(), viewer.uniqueId, "Town", world.uid, CivPosition(0, 64, 0),
            96, "nordic", Stock(), listOf(hall), listOf(CivWorker(workerId, "Resident", null))))
        every { packets.spawnPlayer(any(), any()) } answers {
            spawns++
            var position = firstArg<Location>().clone()
            var valid = true
            var npcScale = 1.0
            var npcPose = PacketNpcPose.STANDING
            mockk<PacketPlayerNpc>(relaxed = true) {
                every { uniqueId } returns workerId
                every { isValid } answers { valid }
                every { location } answers { position.clone() }
                every { world } returns this@ResidentRuntime.world
                every { height } answers { 1.8 * npcScale }
                every { eyeHeight } answers { 1.62 * npcScale }
                every { scale } answers { npcScale }
                every { scale = any() } answers { npcScale = firstArg() }
                every { pose } answers { npcPose }
                every { pose = any() } answers { npcPose = firstArg() }
                every { teleport(any()) } answers { position = firstArg<Location>().clone(); Unit }
                every { setEquipment(EquipmentSlot.HAND, any()) } answers { displayedHand = secondArg<ItemStack>().type; Unit }
                every { remove() } answers { valid = false; Unit }
            }
        }
        every { packets.closeAsync() } returns CompletableFuture.completedFuture(null)
        owner = CivsCitizens(plugin, { if (viewing) listOf(city) else emptyList() },
            { _, _, _, _, _, _, _ ->
                check(work != null) { "idle resident cannot deliver" }
                deliveries++
                pendingDelivery
            },
            { _, _, _ -> }, hurt = { _, _, hit -> hits++; damage = hit; pending },
            footstep = { _, _, _, from, to -> steps += from.clone() to to.clone() },
            courierPlan = { _, _ -> courier }, dropoffCargo = { _, _, cargo, target ->
                cargo shouldBe requireNotNull(courier).cargo
                target shouldBe requireNotNull(courier).destinationBuildingId
                unloads++
                pendingUnload
            },
            workPlan = { _, _ -> work }, workIdleReason = { _, _ -> idleReason }, packetOwner = packets)
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    fun hit(amount: Double) = owner.damage(workerId, amount)
    fun beginCare(status: String): CivWorldWork {
        val farm = assignFarm()
        city = city.copy(workers = city.workers.map { worker -> worker.copy(items =
            MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { slots ->
                slots[4] = CivResidentItem("IRON_HOE", 1)
                if (status == "fertilizing") slots[6] = CivResidentItem("BONE_MEAL", 1)
            }) })
        owner.collisionActors().single().teleport(Location(world, 22.5, 64.0, .5))
        val focus = CivPosition(23, 64, 0)
        world.getBlockAt(focus.x, focus.y, focus.z).type = Material.WHEAT
        work = CivWorldWork(workerId, 0, farm.id, CivPosition(24, 64, 0),
            if (status == "fertilizing") listOf(CivPlanBlock(focus, "minecraft:wheat[age=5]", "minecraft:wheat[age=6]"))
                else emptyList(),
            inputs = if (status == "fertilizing") mapOf("BONE_MEAL" to 1L) else emptyMap(),
            durationMillis = 4_000, status = status, focus = focus)
        owner.pulse(2_000)
        return requireNotNull(work)
    }
    fun commitWork() {
        val completed = requireNotNull(work)
        city = city.copy(revision = city.revision + 1, workers = city.workers.map { worker ->
            CivsEquipment.consumeInputs(worker, completed.inputs).copy(completedCycles = worker.completedCycles + 1)
        })
        work = null
        idleReason = "waiting-growth"
        pendingDelivery.complete(city)
    }
    fun assignFarm(): CivBuilding {
        val farm = CivBuilding(UUID.randomUUID(), BuildingType.FARM, 1, CivPosition(20, 64, 0), 0,
            "farm1", CivBox(CivPosition(18, 64, -2), CivPosition(26, 68, 2)), CivPosition(20, 64, 0),
            BuildingPhase.ACTIVE, control = CivPosition(20, 64, 0), production = "WHEAT")
        for (z in -1..1) world.loadChunk(2, z)
        world.getBlockAt(20, 64, 0).type = Material.BARREL
        city = CivsRules.validate(city.copy(buildings = city.buildings + farm,
            workers = city.workers.map { it.copy(assignment = farm.id, nextDeliveryAt = 1) },
            inventory = CivInventory(CivStorageMode.PHYSICAL)))
        return farm
    }
    fun assignQuarry(): CivBuilding = assignFarm().copy(type = BuildingType.QUARRY, schematicId = "miner1", production = "COBBLESTONE").also { quarry ->
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == quarry.id) quarry else it }))
    }
    fun commitHit() {
        city = CivsRules.hurtResident(city, city.owner, workerId, damage)
        pending.complete(city)
    }
}

/** A stone plane with no tile entities. Only pinned MockBukkit's missing terrain queries are supplied. */
private class ResidentRuntimeWorld : WorldMock(Material.STONE, 63) {
    val particles = mutableListOf<Triple<Particle, Int, Location>>()
    val sounds = mutableListOf<Triple<Sound, Float, Float>>()
    private val cells = mutableMapOf<Coordinate, BlockMock>()
    private val chunkFixtures = mutableMapOf<Pair<Int, Int>, ChunkMock>()
    init { setName("resident-runtime") }

    override fun spawnParticle(particle: Particle, location: Location, count: Int,
        offsetX: Double, offsetY: Double, offsetZ: Double, extra: Double) {
        particles += Triple(particle, count, location.clone())
    }

    override fun playSound(location: Location, sound: Sound, category: SoundCategory, volume: Float, pitch: Float) {
        sounds += Triple(sound, volume, pitch)
    }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))
    override fun getBlockAt(point: Coordinate): BlockMock = cells.getOrPut(point) {
        object : BlockMock(if (point.y <= 63) Material.STONE else Material.AIR,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            override fun isPassable(): Boolean = type.isAir
            override fun getBoundingBox(): BoundingBox = BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(),
                x + 1.0, y + if (type.isAir) 0.0 else 1.0, z + 1.0)
            override fun getCollisionShape(): VoxelShape = if (type.isAir) EMPTY_SHAPE else FULL_SHAPE
        }
    }

    /** Pinned WorldMock omits this native API. This fixture contains only full cubes and air. */
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? {
        val end = start.clone().add(direction.clone().normalize().multiply(maxDistance))
        var nearest: RayTraceResult? = null
        for (x in minOf(start.blockX, end.blockX)..maxOf(start.blockX, end.blockX))
            for (y in minOf(start.blockY, end.blockY)..maxOf(start.blockY, end.blockY))
                for (z in minOf(start.blockZ, end.blockZ)..maxOf(start.blockZ, end.blockZ)) {
                    val block = getBlockAt(x, y, z)
                    if (block.type.isAir) continue
                    val hit = block.boundingBox.rayTrace(start.toVector(), direction, maxDistance) ?: continue
                    if (nearest == null || hit.hitPosition.distanceSquared(start.toVector()) < nearest.hitPosition.distanceSquared(start.toVector()))
                        nearest = RayTraceResult(hit.hitPosition, block, hit.hitBlockFace)
                }
        return nearest
    }

    override fun getChunkAt(x: Int, z: Int, generate: Boolean): ChunkMock {
        val native = super.getChunkAt(x, z, generate)
        return chunkFixtures.getOrPut(x to z) {
            spyk(native).also { chunk ->
                every { chunk.getTileEntities(any<Predicate<Block>>(), false) } returns emptyList<BlockState>()
            }
        }
    }

    companion object {
        private fun shape(boxes: List<BoundingBox>) = object : VoxelShape {
            override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
            override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
        }
        private val EMPTY_SHAPE = shape(emptyList())
        private val FULL_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
    }
}
