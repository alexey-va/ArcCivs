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
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
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
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate

class CivsResidentsRuntimeTest : FunSpec({
    test("monster defence recruits only nearby residents of its victim's city and limits visible melee to once a second") {
        residentRuntime { h ->
            val victim = h.owner.collisionActors().single()
            victim.teleport(Location(h.world, 4.5, 64.0, .5))
            val monster = h.monsterAt(6.5, .5)
            // Discovery occurs while only the victim exists. Later helpers must be recruited
            // by the actual pursuit callback, not become independent targets of the scan.
            h.owner.pulse(2_000)
            h.world.threatEntities.clear()
            val helperId = UUID.randomUUID()
            val distantId = UUID.randomUUID()
            val outsiderId = UUID.randomUUID()
            h.city = h.city.copy(workers = h.city.workers + listOf(
                CivWorker(helperId, "Helper", null), CivWorker(distantId, "Distant", null)))
            h.otherCities += h.city.copy(id = UUID.randomUUID(), workers = listOf(CivWorker(outsiderId, "Outsider", null)))
            h.owner.pulse(3_000)
            h.owner.pulse(4_000)
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.size shouldBe 4
            val helper = actors.getValue(helperId)
            val distant = actors.getValue(distantId)
            val outsider = actors.getValue(outsiderId)
            victim.teleport(Location(h.world, 4.5, 64.0, .5))
            helper.teleport(Location(h.world, 4.5, 64.0, 1.5))
            distant.teleport(Location(h.world, 18.5, 64.0, .5))
            outsider.teleport(Location(h.world, 4.5, 64.0, -1.5))
            val residents = h.city.workers
            h.owner.activateNavigating()
            h.owner.pulse(5_000)
            h.owner.status(h.workerId) shouldBe "workers.status.defending"
            h.owner.status(helperId) shouldBe "workers.status.defending"
            h.owner.status(distantId) shouldBe "workers.status.unassigned"
            h.owner.status(outsiderId) shouldBe "workers.status.unassigned"
            verify(exactly = 1) { victim.swingMainHand() }
            verify(exactly = 1) { helper.swingMainHand() }
            verify(exactly = 0) { distant.swingMainHand() }
            verify(exactly = 0) { outsider.swingMainHand() }
            verify(exactly = 2) { monster.damage(1.0) }
            for (at in 5_100L..5_900L step 100) h.owner.pulse(at)
            verify(exactly = 2) { monster.damage(any<Double>()) }
            h.owner.pulse(6_000)
            verify(exactly = 4) { monster.damage(1.0) }

            // A newly placed native wall blocks both residents' next strikes even though
            // the pursuit is already active and the monster stays within melee distance.
            for (y in 64..66) for (z in 0..1) h.world.getBlockAt(5, y, z).type = Material.STONE
            h.owner.pulse(7_000)
            verify(exactly = 4) { monster.damage(any<Double>()) }
            for (y in 64..66) for (z in 0..1) h.world.getBlockAt(5, y, z).type = Material.AIR
            h.owner.pulse(8_000)
            verify(exactly = 6) { monster.damage(1.0) }
            h.city.workers shouldBe residents
            h.deliveries shouldBe 0
            h.hits shouldBe 0
        }
    }

    test("wounded adults and children walk away from a pursuing monster without attacking or earning work credit") {
        for (child in listOf(false, true)) residentRuntime { h ->
            h.city = h.city.copy(workers = h.city.workers.map { if (child)
                it.copy(childMinutes = 1, parentIds = setOf(UUID.randomUUID(), UUID.randomUUID()))
                else it.copy(health = 7.0) })
            val victim = h.owner.collisionActors().single()
            val start = Location(h.world, 10.5, 64.0, .5)
            victim.teleport(start)
            val monster = h.monsterAt(12.5, .5)
            h.owner.pulse(2_000)
            h.owner.activateNavigating()
            h.owner.pulse(3_000)
            h.owner.status(h.workerId) shouldBe "workers.status.retreating"
            for (tick in 0 until 2_000) {
                val before = victim.location
                h.owner.activateNavigating()
                (before.distance(victim.location) <= .236501) shouldBe true
                if (victim.location.distanceSquared(start) >= 4.0 * 4.0) break
            }
            (victim.location.x < start.x - 3.0) shouldBe true
            (victim.location.distanceSquared(monster.location) > start.distanceSquared(monster.location)) shouldBe true
            verify(exactly = 0) { victim.swingMainHand() }
            verify(exactly = 0) { monster.damage(any<Double>()) }
            h.city.workers.single().completedCycles shouldBe 0
            h.city.workers.single().skills.orEmpty() shouldBe emptyMap()
            h.deliveries shouldBe 0
            h.hits shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("a processor collects supplies only at its barrel and cannot start work before the receipt") {
        residentRuntime { h ->
            val mill = h.assignFarm().copy(type = BuildingType.SAWMILL, production = null)
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == mill.id) mill else it })
            h.workReady = false
            val actor = h.owner.collisionActors().single()
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            h.owner.pulse(2_000)
            h.supplyRequests shouldBe 0
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 19.5, 64.0, .5))
            h.owner.pulse(3_000)
            h.supplyRequests shouldBe 1
            h.owner.requireStorageArrival(h.city.id, h.workerId, mill.id)
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.loading-cargo"
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
            actor.teleport(Location(h.world, 15.5, 64.0, .5))
            shouldThrow<CivsRejection> { h.owner.requireStorageArrival(h.city.id, h.workerId, mill.id) }
            actor.teleport(Location(h.world, 19.5, 64.0, .5))
            h.workReady = true
            h.pendingSupplies.complete(h.city)
            h.owner.pulse(5_000)
            h.owner.pulse(6_000)
            h.owner.status(h.workerId) shouldBe "workers.status.working"
            h.supplyRequests shouldBe 1
            h.deliveries shouldBe 0
        }
    }

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

    test("a miner waits on its ladder while the next job is searched instead of climbing toward an idle surface walk") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            val actor = h.owner.collisionActors().single()
            for (y in 57..64) h.world.getBlockAt(18, y, 0).type = Material.LADDER
            val start = Location(h.world, 18.5, 61.4, .5)
            actor.teleport(start)
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(18, 61, 0),
                listOf(CivPlanBlock(CivPosition(19, 62, 0), "minecraft:stone", "minecraft:air")), emptyMap(), emptyMap(),
                durationMillis = 2_000, focus = CivPosition(19, 62, 0), status = "digging-mine")
            h.owner.pulse(2_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            // This is the real cache gap after a commit / invalidation. Its surface anchor
            // is valid and reachable, so a misplaced idle walk would visibly climb the shaft.
            h.work = null
            h.idleReason = "searching-work"
            h.owner.pulse(3_000)
            repeat(160) { h.owner.activateNavigating() }
            actor.location.distanceSquared(start) shouldBe 0.0
            h.owner.status(h.workerId) shouldBe "workers.status.searching-work"
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(18, 57, 0),
                listOf(CivPlanBlock(CivPosition(18, 56, 0), "minecraft:stone", "minecraft:air")), emptyMap(), emptyMap(),
                durationMillis = 2_000, focus = CivPosition(18, 56, 0), status = "digging-mine")
            h.owner.pulse(4_000)
            repeat(1_000) {
                h.owner.activateNavigating()
                (actor.location.y <= start.y) shouldBe true
            }
            (actor.location.y < start.y - .1) shouldBe true
            h.owner.pulse(4_000)
            h.owner.status(h.workerId) shouldBe "workers.status.digging-mine"
            h.deliveries shouldBe 0
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

    test("removing a sleeping resident's floor wakes it at logical feet and normal gravity settles it") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, 1.5, 64.0, .5)
            actor.teleport(origin)
            h.world.time = 13_000
            h.owner.pulse(2_000)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            h.owner.residentFeet(h.workerId) shouldBe origin
            h.world.getBlockAt(1, 63, 0).type = Material.AIR
            h.owner.pulse(3_000)
            actor.pose shouldBe PacketNpcPose.STANDING
            actor.location shouldBe origin
            repeat(30) { h.owner.activateNavigating() }
            actor.location.y shouldBe 63.0
            citizenPacketSupported(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z)) shouldBe true
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
        }
    }

    test("a new ceiling cannot force a sleeper to stand inside stone or work before a safe wake space opens") {
        residentRuntime { h ->
            val actor = h.owner.collisionActors().single()
            val origin = Location(h.world, 1.5, 64.0, .5)
            val lying = requireNotNull(citizenSleepPose(h.world, origin, 1.0)).bounds
            actor.teleport(origin)
            h.world.time = 13_000
            h.owner.pulse(2_000)
            actor.pose shouldBe PacketNpcPose.SLEEPING
            val sleepingPosition = actor.location
            for (x in -2..2) for (z in -2..2) h.world.getBlockAt(x, 65, z).type = Material.STONE
            citizenSleepClear(h.world, lying) shouldBe true
            citizenPacketBodyClear(h.world, CitizenPoint(origin.x, origin.y, origin.z), false) shouldBe false
            h.world.time = 0
            for (now in listOf(3_000L, 4_000L)) {
                h.owner.pulse(now)
                repeat(40) { h.owner.activateNavigating() }
                actor.pose shouldBe PacketNpcPose.SLEEPING
                actor.location shouldBe sleepingPosition
                h.owner.workingResidentIds(h.city.id) shouldBe emptySet()
                h.hits shouldBe 0
                (h.owner.status(h.workerId) == "workers.status.injury-pending") shouldBe false
            }
            // There is now a supported spot in the same home with a clear low-body sweep.
            h.world.getBlockAt(2, 65, 0).type = Material.AIR
            h.owner.pulse(5_000)
            actor.pose shouldBe PacketNpcPose.STANDING
            h.hits shouldBe 0
            actor.location.x shouldBe 2.5
            actor.location.y shouldBe origin.y
            citizenPacketBodyClear(h.world, CitizenPoint(actor.location.x, actor.location.y, actor.location.z), false) shouldBe true
            h.deliveries shouldBe 0
            h.city.workers.single().completedCycles shouldBe 0
            h.steps shouldBe emptyList()
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
            verify { actor.sleepingBed = null }
            val lying = actor.location
            (lying.y > recovered.y) shouldBe true
            h.owner.residentFeet(h.workerId) shouldBe recovered
            h.owner.residentFeet(h.workerId)?.add(0.0, 10.0, 0.0) // Caller mutation cannot alter the saved feet.
            h.owner.residentFeet(h.workerId) shouldBe recovered
            repeat(20) { h.owner.activateNavigating() }
            actor.location.y shouldBe lying.y // Gravity must not undo the pose-only floor clearance.
            // The pose is rechecked against the current world, not trusted until morning.
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                h.world.getBlockAt(recovered.blockX + dx, recovered.blockY, recovered.blockZ + dz).type = Material.STONE
            h.owner.pulse(16_001)
            actor.pose shouldBe PacketNpcPose.STANDING
            actor.location.y shouldBe recovered.y
            h.owner.status(h.workerId) shouldBe "workers.status.sheltering"
            h.owner.residentFeet(h.workerId) shouldBe recovered
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
    var supplyRequests = 0
    var workReady = true
    val pendingSupplies = CompletableFuture<Settlement>()
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
    val otherCities = mutableListOf<Settlement>()
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
            val profile = secondArg<PacketNpcProfile>()
            var valid = true
            var npcScale = 1.0
            var npcPose = PacketNpcPose.STANDING
            mockk<PacketPlayerNpc>(relaxed = true) {
                every { uniqueId } returns profile.uuid
                every { isValid } answers { valid }
                every { location } answers { position.clone() }
                every { world } returns this@ResidentRuntime.world
                every { height } answers { when (npcPose) {
                    PacketNpcPose.STANDING -> 1.8 * npcScale
                    PacketNpcPose.CROUCHING -> 1.5 * npcScale
                    PacketNpcPose.SLEEPING -> .2
                } }
                every { eyeHeight } answers { when (npcPose) {
                    PacketNpcPose.STANDING -> 1.62 * npcScale
                    PacketNpcPose.CROUCHING -> 1.27 * npcScale
                    PacketNpcPose.SLEEPING -> .2
                } }
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
        owner = CivsCitizens(plugin, { if (viewing) listOf(city) + otherCities else emptyList() },
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
            workPlan = { _, _ -> work }, workIdleReason = { _, _ -> idleReason }, packetOwner = packets,
            workInputsReady = { _, _ -> workReady }, prepareSupplies = { _, _, _ ->
                supplyRequests++
                pendingSupplies
            })
        owner.pulse(1_000)
        owner.activeCount() shouldBe 1
    }

    fun monsterAt(x: Double, z: Double): Monster {
        world.difficulty = Difficulty.NORMAL
        val at = Location(world, x, 64.0, z)
        return mockk<Monster>(relaxed = true) {
            every { uniqueId } returns UUID.randomUUID()
            every { isValid } returns true
            every { isDead } returns false
            every { hasAI() } returns true
            every { type } returns EntityType.ZOMBIE
            every { world } returns this@ResidentRuntime.world
            every { location } answers { at.clone() }
            every { eyeLocation } answers { at.clone().add(0.0, 1.62, 0.0) }
            every { target } returns null
        }.also(world.threatEntities::add)
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
    val threatEntities = mutableListOf<Entity>()
    val particles = mutableListOf<Triple<Particle, Int, Location>>()
    val sounds = mutableListOf<Triple<Sound, Float, Float>>()
    private val cells = mutableMapOf<Coordinate, BlockMock>()
    private val chunkFixtures = mutableMapOf<Pair<Int, Int>, ChunkMock>()
    init { setName("resident-runtime") }

    override fun getNearbyEntities(location: Location, x: Double, y: Double, z: Double,
        filter: Predicate<in Entity>?): Collection<Entity> = threatEntities.filter { entity ->
        val at = entity.location
        kotlin.math.abs(at.x - location.x) <= x && kotlin.math.abs(at.y - location.y) <= y &&
            kotlin.math.abs(at.z - location.z) <= z && filter?.test(entity) != false
    }

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
            override fun getBoundingBox(): BoundingBox = if (type == Material.LADDER)
                BoundingBox(x.toDouble(), y.toDouble(), z + 13.0 / 16.0, x + 1.0, y + 1.0, z + 1.0)
            else BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + if (type.isAir) 0.0 else 1.0, z + 1.0)
            override fun getCollisionShape(): VoxelShape = when {
                type.isAir -> EMPTY_SHAPE
                type == Material.LADDER -> LADDER_SHAPE
                else -> FULL_SHAPE
            }
        }
    }

    /** Pinned WorldMock omits this native API. The fixture adds the default north-facing ladder shape. */
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
        private val LADDER_SHAPE = shape(listOf(BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0)))
    }
}
