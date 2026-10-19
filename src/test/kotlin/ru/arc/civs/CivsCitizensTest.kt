package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsCitizensTest : FunSpec({
    test("external activation only ticks our spawned moving NPC with a viewer and an entity ticking route") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("vanilla")
            world.loadChunk(-1, -1)
            val registry = mockk<NPCRegistry>()
            val npc = mockk<NPC>()
            val entity = mockk<Entity>()
            every { npc.owningRegistry } returns registry
            every { npc.isSpawned } returns true
            every { npc.navigator.isNavigating } returns true
            every { npc.entity } returns entity
            every { entity.isValid } returns true
            every { entity.world } returns world
            every { entity.location } returns Location(world, -8.9, 1.0, -11.5)
            var viewers = listOf(CitizenViewer(world.uid, CitizenPoint(-60.5, 1.0, -7.5)))
            var loaded = true
            var activations = 0
            fun tick() = activateCitizenRoute(npc, registry, world.uid, viewers, 96, { loaded }) {
                it shouldBe entity
                activations++
            }

            repeat(2) { tick() shouldBe true }
            activations shouldBe 2
            loaded = false
            tick() shouldBe false
            loaded = true
            viewers = listOf(CitizenViewer(world.uid, CitizenPoint(90.0, 1.0, -11.5)))
            tick() shouldBe false
            viewers = listOf(CitizenViewer(UUID.randomUUID(), CitizenPoint(-8.9, 1.0, -11.5)))
            tick() shouldBe false
            viewers = listOf(CitizenViewer(world.uid, CitizenPoint(-60.5, 1.0, -7.5)))
            every { npc.navigator.isNavigating } returns false
            tick() shouldBe false
            every { npc.navigator.isNavigating } returns true
            every { npc.isSpawned } returns false
            tick() shouldBe false
            every { npc.isSpawned } returns true
            every { entity.isValid } returns false
            tick() shouldBe false
            every { entity.isValid } returns true
            every { npc.owningRegistry } returns mockk<NPCRegistry>()
            tick() shouldBe false
            activations shouldBe 2
            every { npc.owningRegistry } returns registry
            world.unloadChunk(-1, -1)
            tick() shouldBe false
            activations shouldBe 2
            world.loadChunk(-1, -1)
            tick() shouldBe true
            activations shouldBe 3
        }
    }

    test("chunk readiness distinguishes loaded terrain from entity ticking without loading chunks") {
        val world = mockk<World>()
        val chunk = mockk<Chunk>()
        every { world.isChunkLoaded(-2, 3) } returns false
        citizenChunkReady(world, -2, 3) shouldBe false
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), any<Boolean>()) }

        every { world.isChunkLoaded(-2, 3) } returns true
        every { world.getChunkAt(-2, 3, false) } returns chunk
        for (level in Chunk.LoadLevel.entries) {
            every { chunk.loadLevel } returns level
            citizenChunkReady(world, -2, 3) shouldBe (level == Chunk.LoadLevel.ENTITY_TICKING)
        }
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
    }

    test("a route sleeps while any checked chunk is non ticking and recovers when simulation returns") {
        val world = mockk<World>()
        val active = mockk<Chunk>()
        val boundary = mockk<Chunk>()
        val chunks = CitizenChunks.between(15, -12, 47, -12)
        chunks shouldBe CitizenChunks(-1, 3, -2, 0)
        every { world.isChunkLoaded(any(), any()) } returns true
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns active
        every { active.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        every { world.getChunkAt(2, -1, false) } returns boundary
        every { boundary.loadLevel } returns Chunk.LoadLevel.TICKING
        repeat(5) { chunks.ready(world) shouldBe false }
        every { boundary.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        chunks.ready(world) shouldBe true
        verify(exactly = 0) { world.getChunkAt(-1, -2, false) }
        verify(exactly = 0) { world.getChunkAt(3, -1, false) }
        every { world.isChunkLoaded(-1, -2) } returns false
        chunks.ready(world) shouldBe false
        every { world.isChunkLoaded(-1, -2) } returns true
        chunks.ready(world) shouldBe true
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
    }

    test("view activation is bounded in three dimensions and never crosses worlds") {
        val world = UUID.randomUUID()
        val origin = CitizenPoint(0.0, 64.0, 0.0)
        citizenHasViewer(world, origin, emptyList(), 96) shouldBe false
        citizenHasViewer(world, origin, listOf(CitizenViewer(world, CitizenPoint(96.0, 64.0, 0.0))), 96) shouldBe true
        citizenHasViewer(world, origin, listOf(CitizenViewer(world, CitizenPoint(96.1, 64.0, 0.0))), 96) shouldBe false
        citizenHasViewer(world, origin, listOf(CitizenViewer(world, CitizenPoint(0.0, 161.0, 0.0))), 96) shouldBe false
        citizenHasViewer(world, origin, listOf(CitizenViewer(UUID.randomUUID(), origin)), 96) shouldBe false
    }

    test("admission preserves both the server cap and two spawns per pulse") {
        citizenSpawnBudget(0, 48) shouldBe 2
        citizenSpawnBudget(47, 48) shouldBe 1
        citizenSpawnBudget(48, 48) shouldBe 0
        citizenSpawnBudget(48, 200) shouldBe 0
        citizenSpawnBudget(8, 8) shouldBe 0
        citizenSpawnBudget(7, 8) shouldBe 1
    }

    test("actual routes allow arrival slack without exceeding the 89 block limit") {
        val origin = CitizenPoint(0.0, 64.0, 0.0)
        CivsRules.MAX_WORK_ROUTE_DISTANCE shouldBe 88
        CITIZEN_ROUTE_ARRIVAL_TOLERANCE shouldBe 1.0
        citizenRouteWithinReach(origin, CitizenPoint(88.65, 64.0, 0.0)) shouldBe true
        citizenRouteWithinReach(origin, CitizenPoint(89.0, 64.0, 0.0)) shouldBe true
        citizenRouteWithinReach(origin, CitizenPoint(89.001, 64.0, 0.0)) shouldBe false
        citizenRouteWithinReach(origin, CitizenPoint(0.0, 153.001, 0.0)) shouldBe false
    }

    test("a native null completion remains distinct from no callback and requires verified arrival") {
        val completed = CitizenNavigationCompletion(null)
        citizenNavigationOutcome(completed, false, 1_000, 10_000, false) shouldBe
            CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
        citizenNavigationOutcome(completed, true, 1_000, 10_000, false) shouldBe CitizenNavigationOutcome.ARRIVED
        citizenNavigationOutcome(null, true, 1_000, 10_000, false) shouldBe CitizenNavigationOutcome.STOPPED
        citizenNavigationOutcome(null, true, 1_000, 10_000, true) shouldBe CitizenNavigationOutcome.PENDING
    }

    test("Citizens cancellation retains its reason and cannot count as an arrival") {
        val cancelled = CitizenNavigationCompletion("STUCK")
        cancelled.cancelReason shouldBe "STUCK"
        citizenNavigationOutcome(cancelled, true, 90_001, 90_000, false) shouldBe CitizenNavigationOutcome.CANCELLED
        citizenNavigationOutcome(null, false, 90_001, 90_000, false) shouldBe CitizenNavigationOutcome.TIMED_OUT
        citizenNavigationOutcome(null, false, 90_000, 90_000, true) shouldBe CitizenNavigationOutcome.PENDING
    }

    test("early native player completion cannot count work and recovery starts a fresh observed interval") {
        val cycle = CitizenCycle(token(), 1_000)
        cycle.start()
        cycle.arrive(0) // Storage reached; the worker has not reached its workplace yet.
        cycle.phase shouldBe CitizenPhase.TO_WORK
        citizenNavigationOutcome(CitizenNavigationCompletion(null), false, 1_000, 10_000, false) shouldBe
            CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
        cycle.work(60_000)
        cycle.phase shouldBe CitizenPhase.TO_WORK
        cycle.beginDelivery(60_000) shouldBe null

        cycle.arrive(61_000) // Only a verified real arrival or safe recovery may make this transition.
        cycle.work(61_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.work(61_999)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.work(62_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(62_000) shouldBe null
    }

    test("path diagnostics preserve the endpoint but inspect at most 256 nodes") {
        citizenPathSnapshot(emptyList()) shouldBe null
        val start = CitizenPoint(-9.0, 1.0, -12.0)
        val end = CitizenPoint(15.0, 1.0, -12.0)
        citizenPathSnapshot(listOf(start, end)) shouldBe CitizenPathSnapshot(2, end, false)
        var reads = 0
        val unbounded = Iterable {
            object : Iterator<CitizenPoint> {
                override fun hasNext() = true
                override fun next(): CitizenPoint {
                    check(reads < 256) { "Diagnostic exceeded its path budget" }
                    return CitizenPoint(reads++.toDouble(), 1.0, -12.0)
                }
            }
        }
        citizenPathSnapshot(unbounded) shouldBe CitizenPathSnapshot(256, CitizenPoint(255.0, 1.0, -12.0), true)
        reads shouldBe 256
    }

    test("storage reassignment resets an actor only beyond the bounded new leg") {
        val previous = CivPosition(0, 64, 0)
        val actual = CitizenPoint(0.5, 64.0, 0.5)
        citizenStorageNeedsReset(previous, CivPosition(89, 64, 0), actual) shouldBe false
        citizenStorageNeedsReset(previous, CivPosition(90, 64, 0), actual) shouldBe true
        citizenStorageNeedsReset(previous, previous, CitizenPoint(500.5, 64.0, 0.5)) shouldBe false
        citizenStorageNeedsReset(previous, CivPosition(90, 64, 0), CitizenPoint(1.5, 64.0, 0.5)) shouldBe false
        citizenStorageNeedsReset(previous, CivPosition(90, 64, 0), CitizenPoint(1.49, 64.0, 0.5)) shouldBe true
    }

    test("a production timer cannot deliver before work arrival and the return trip") {
        val cycle = CitizenCycle(token(), 60_000)
        cycle.work(100_000)
        cycle.beginDelivery(100_000) shouldBe null
        cycle.start()
        cycle.work(100_000)
        cycle.phase shouldBe CitizenPhase.TO_STORAGE
        cycle.arrive(100_000)
        cycle.phase shouldBe CitizenPhase.TO_WORK
        cycle.arrive(100_000)
        repeat(59) { cycle.work(101_000L + it * 1_000) }
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.beginDelivery(200_000) shouldBe null
        cycle.work(160_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(200_000) shouldBe null
        cycle.arrive(161_000)
        cycle.beginDelivery(161_000)?.let(cycle::acceptsCompletion) shouldBe true
        cycle.beginDelivery(161_001) shouldBe null
    }

    test("incremental builder ends a two second observed session at work without a return delivery trip") {
        val cycle = CitizenCycle(token(), CivsRules.BUILDER_SESSION_INTERVAL, completeAtWorkplace = true)
        cycle.start()
        cycle.work(100_000)
        cycle.beginDelivery(100_000) shouldBe null
        cycle.arrive(100_000)
        cycle.arrive(100_000)
        cycle.work(101_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.work(102_000)
        cycle.phase shouldBe CitizenPhase.READY
        cycle.beginDelivery(102_000)?.let(cycle::acceptsCompletion) shouldBe true
        cycle.beginDelivery(102_001) shouldBe null
    }

    test("arrival after a recovery teleport never credits time known to have been away from work") {
        val cycle = CitizenCycle(token(), 2_000, completeAtWorkplace = true)
        reachWork(cycle)
        cycle.arrive(1_000) // Recovery after observing the NPC away from the workplace.
        cycle.work(2_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.beginDelivery(2_000) shouldBe null
        cycle.work(3_000)
        cycle.phase shouldBe CitizenPhase.READY
    }

    test("navigation keeps retrying safely after three failures instead of an eternal pause") {
        val cycle = CitizenCycle(token(), 2_000, completeAtWorkplace = true)
        cycle.start()
        repeat(5) { attempt ->
            val now = attempt * 20_000L
            cycle.pause("route-unreachable", now, false)
            cycle.retry(now + 15_000)
            cycle.phase shouldBe CitizenPhase.TO_STORAGE
            cycle.beginDelivery(now + 15_000) shouldBe null
        }
        cycle.arrive(100_000)
        cycle.arrive(100_000)
        cycle.work(101_000)
        cycle.work(102_000)
        cycle.phase shouldBe CitizenPhase.READY
    }

    test("two unsafe recovery targets cannot starve a third safe actor on synchronized retries") {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val safe = UUID.randomUUID()
        val actors = linkedMapOf(first to false, second to false, safe to true)
        val examined = mutableListOf<UUID>()
        fun pulse(): UUID? {
            var remaining = 2
            for (id in actors.keys.toList()) {
                if (remaining == 0) break
                remaining--
                citizenRotateAfterAdmission(actors, id)
                examined += id
                if (actors.getValue(id)) return id
            }
            return null
        }
        pulse() shouldBe null
        examined shouldBe listOf(first, second)
        pulse() shouldBe safe
        examined shouldBe listOf(first, second, safe)
    }

    test("safe teleport selection is bounded near the real target and excludes building interiors and void") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Builder", hall.id)
        val settlement = city(listOf(hall), worker, null)
        val target = CivPosition(20, 64, 20)
        var checks = 0
        citizenRecoveryTarget(settlement, target) { checks++; false } shouldBe null
        checks shouldBe 27
        citizenRecoveryTarget(settlement, target) { it == target } shouldBe target
        val alternate = CivPosition(21, 65, 20)
        citizenRecoveryTarget(settlement, target) { it == alternate } shouldBe alternate
        citizenRecoveryTarget(settlement, hall.bounds.min) { hall.bounds.contains(it.x, it.y, it.z) } shouldBe null
        citizenRecoveryTarget(settlement, CivPosition(settlement.center.x + settlement.radius + 2, 64, 0)) { true } shouldBe null
    }

    test("builder work is ten observed seconds and an inactive interval never catches up") {
        val cycle = CitizenCycle(token(nextDeliveryAt = 100_000), 10_000)
        reachWork(cycle)
        cycle.work(60_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        repeat(10) { cycle.work(61_000L + it * 1_000) }
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.arrive(71_000)
        cycle.beginDelivery(99_999) shouldBe null
        cycle.beginDelivery(100_000)?.let(cycle::acceptsCompletion) shouldBe true
    }

    test("a failed trip discards partial work and retries from storage") {
        val cycle = CitizenCycle(token(), 10_000)
        reachWork(cycle)
        repeat(9) { cycle.work(1_000L + it * 1_000) }
        cycle.pause("route-unreachable", 9_000, false)
        cycle.retry(13_999)
        cycle.phase shouldBe CitizenPhase.PAUSED
        cycle.retry(14_000)
        cycle.phase shouldBe CitizenPhase.TO_STORAGE
        cycle.arrive(14_000)
        cycle.arrive(15_000)
        cycle.work(16_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.beginDelivery(16_000) shouldBe null
    }

    test("closed and replaced deliveries cannot revive an actor") {
        val token = token()
        val old = CitizenCycle(token, 1_000)
        reachWork(old)
        old.work(1_000)
        old.arrive(2_000)
        val ticket = requireNotNull(old.beginDelivery(2_000))
        old.acceptsCompletion(ticket) shouldBe true
        old.close()
        old.acceptsCompletion(ticket) shouldBe false
        old.retry(Long.MAX_VALUE)
        old.resume()
        old.start()
        old.phase shouldBe CitizenPhase.CLOSED
        val replacement = CitizenCycle(token.copy(nextDeliveryAt = token.nextDeliveryAt + 1), 1_000)
        replacement.acceptsCompletion(ticket) shouldBe false
        (replacement.token == old.token) shouldBe false
        (token.copy(assignmentId = UUID.randomUUID()) == token) shouldBe false
        (token.copy(completedCycles = 1) == token) shouldBe false
        (token.copy(constructionId = UUID.randomUUID()) == token) shouldBe false
    }

    test("storage-full retains one completed trip but replaces its failed delivery identity") {
        val cycle = CitizenCycle(token(), 1_000)
        reachWork(cycle)
        cycle.work(1_000)
        cycle.arrive(2_000)
        val old = requireNotNull(cycle.beginDelivery(2_000))
        cycle.pause("storage-full", 3_000, true)
        cycle.acceptsCompletion(old) shouldBe false
        cycle.retry(8_000)
        cycle.phase shouldBe CitizenPhase.READY
        val retry = requireNotNull(cycle.beginDelivery(8_000))
        (old == retry) shouldBe false
        cycle.acceptsCompletion(old) shouldBe false
        cycle.acceptsCompletion(retry) shouldBe true
    }

    test("four workers retain completed trips through repeated busy commits and earn only once") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val foresters = (1..4).map { building(BuildingType.FORESTER, CivPosition(it * 5, 64, 0)) }
        val workers = foresters.mapIndexed { index, building ->
            CivWorker(UUID.randomUUID(), "Worker ${index + 1}", building.id, nextDeliveryAt = 1)
        }
        var city = CivsRules.validate(city(listOf(hall) + foresters, workers.first(), null).copy(workers = workers))
        val cycles = workers.map { worker ->
            CitizenCycle(requireNotNull(citizenPlan(city, worker)).token, 1_000).also {
                reachWork(it)
                it.work(1_000)
                it.arrive(2_000)
            }
        }
        var now = 2_000L
        repeat(5) {
            cycles.forEach { cycle ->
                val ticket = requireNotNull(cycle.beginDelivery(now))
                cycle.acceptsCompletion(ticket) shouldBe true
                cycle.failDelivery("city-busy", now)
                cycle.acceptsCompletion(ticket) shouldBe false
                cycle.status() shouldBe "waiting"
                cycle.retry(now + 4_999)
                cycle.phase shouldBe CitizenPhase.PAUSED
                cycle.beginDelivery(now + 4_999) shouldBe null
                cycle.retry(now + 5_000)
                cycle.phase shouldBe CitizenPhase.READY
            }
            now += 5_000
        }
        city.stock shouldBe Stock()
        cycles.forEachIndexed { index, cycle ->
            val worker = workers[index]
            val ticket = requireNotNull(cycle.beginDelivery(now))
            cycle.acceptsCompletion(ticket) shouldBe true
            city = CivsRules.deliver(city, city.owner, worker.id, requireNotNull(worker.assignment),
                worker.completedCycles, worker.nextDeliveryAt, now)
            cycle.close()
            cycle.acceptsCompletion(ticket) shouldBe false
            cycle.failDelivery("city-busy", now)
            cycle.retry(now + 5_000)
            cycle.phase shouldBe CitizenPhase.CLOSED
            cycle.beginDelivery(now + 5_000) shouldBe null
            shouldThrow<CivsRejection> {
                CivsRules.deliver(city, city.owner, worker.id, requireNotNull(worker.assignment),
                    worker.completedCycles, worker.nextDeliveryAt, now)
            }.reason shouldBe "stale-delivery"
        }
        city.stock shouldBe Stock(wood = 16)
        city.workers.map { it.completedCycles } shouldBe listOf(1L, 1L, 1L, 1L)
        city.revision shouldBe 4L
    }

    test("route and spawn failures get only two timed automatic retries") {
        val retry = CitizenRetry()
        retry.ready(0) shouldBe true
        retry.fail(100)
        retry.ready(5_099) shouldBe false
        retry.ready(5_100) shouldBe true
        retry.fail(5_100)
        retry.ready(20_099) shouldBe false
        retry.ready(20_100) shouldBe true
        retry.fail(20_100)
        retry.exhausted shouldBe true
        retry.ready(Long.MAX_VALUE) shouldBe false
    }

    test("builder captures construction identity and its planned upgrade entrance") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val farm = building(BuildingType.FARM, CivPosition(8, 64, 0))
        val upgradedFarm = farm.copy(level = 2, entrance = CivPosition(12, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Иван", hall.id, 3, 25_000)
        val order = CivConstruction(UUID.randomUUID(), farm.id, 2, 6, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = upgradedFarm)
        val city = city(listOf(hall, farm), worker, order)
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.workplace shouldBe upgradedFarm.entrance
        plan.token shouldBe CitizenToken(hall.id, 3, 25_000, order.id)
        plan.workMillis shouldBe CivsRules.BUILDER_INTERVAL
        plan.idleReason shouldBe null
        citizenPlan(city.copy(construction = null), worker)?.idleReason shouldBe "no-construction"
        citizenPlan(city.copy(construction = order.copy(phase = ConstructionPhase.PLACING)), worker)?.idleReason shouldBe "placing"
        citizenPlan(city, worker.copy(assignment = null))?.idleReason shouldBe "unassigned"
    }

    test("a site hall supplies a depot while a builder assigned to its unfinished hut can build that hut") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(phase = BuildingPhase.SITE)
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(8, 64, 0)).copy(phase = BuildingPhase.CONSTRUCTING)
        val worker = CivWorker(UUID.randomUUID(), "Строитель", hut.id, nextDeliveryAt = 10_000)
        val order = CivConstruction(UUID.randomUUID(), hut.id, 1, 2, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = hut, batches = CivConstructionBatches(2), materials = emptyMap())
        val city = city(listOf(hall, hut), worker, order)
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.storage shouldBe hall.entrance
        plan.workplace shouldBe hut.entrance
        plan.workMillis shouldBe CivsRules.BUILDER_INTERVAL
        plan.idleReason shouldBe null
        citizenPlan(city, worker.copy(assignment = hall.id))?.idleReason shouldBe "building-unavailable"
        val otherOrder = order.copy(buildingId = hall.id, plannedBuilding = hall.copy(phase = BuildingPhase.CONSTRUCTING))
        citizenPlan(city.copy(construction = otherOrder), worker)?.idleReason shouldBe "building-unavailable"
        citizenPlan(city.copy(construction = null), worker.copy(assignment = null))?.idleReason shouldBe "unassigned"
    }

    test("upgrading producers pause while a builder continues rebuilding its own active hut") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(8, 64, 0))
        val farm = building(BuildingType.FARM, CivPosition(12, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Житель", farm.id, nextDeliveryAt = 10_000)
        val order = CivConstruction(UUID.randomUUID(), farm.id, 2, 2, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = farm.copy(level = 2, phase = BuildingPhase.CONSTRUCTING),
            batches = CivConstructionBatches(2), materials = emptyMap())
        val city = city(listOf(hall, hut, farm), worker, order)
        citizenPlan(city, worker)?.idleReason shouldBe "building-unavailable"
        val selfUpgrade = order.copy(buildingId = hut.id, plannedBuilding = hut.copy(level = 2, phase = BuildingPhase.CONSTRUCTING))
        citizenPlan(city.copy(construction = selfUpgrade), worker.copy(assignment = hut.id))?.idleReason shouldBe null
    }

    test("idle wander remains bounded and timed without advancing a production cycle") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(phase = BuildingPhase.SITE)
        val worker = CivWorker(UUID.randomUUID(), "Свободный", null)
        val city = city(listOf(hall), worker, null)
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.idleReason shouldBe "unassigned"
        val wander = CitizenWander()
        val cycle = CitizenCycle(plan.token, plan.workMillis)
        wander.ready(0) shouldBe true
        val targets = (0..39).map { requireNotNull(citizenWanderTarget(city, hall.entrance, worker.id, it)) }
        targets.distinct().size shouldBe 40
        targets.all { city.contains(it.x, it.z) && kotlin.math.abs(it.x) <= 4 && kotlin.math.abs(it.z) <= 4 && it.y in 62..66 } shouldBe true
        targets.take(5).map { it.x to it.z }.distinct().size shouldBe 1
        targets.take(5).map { it.y } shouldBe listOf(64, 65, 63, 66, 62)
        targets[5].let { it.x to it.z } shouldBe targets[9].let { it.x to it.z }
        (targets[0].let { it.x to it.z } != targets[5].let { it.x to it.z }) shouldBe true
        wander.reject(0)
        wander.ready(999) shouldBe false
        wander.ready(1_000) shouldBe true
        wander.step shouldBe 1
        wander.finish(1_000, true)
        wander.step shouldBe 5
        wander.ready(5_999) shouldBe false
        wander.ready(6_000) shouldBe true
        wander.finish(6_000, false)
        wander.ready(20_999) shouldBe false
        wander.ready(21_000) shouldBe true
        cycle.phase shouldBe CitizenPhase.IDLE
        cycle.beginDelivery(Long.MAX_VALUE) shouldBe null
        worker.completedCycles shouldBe 0L
        city.stock shouldBe Stock()
        (0..39).all { citizenWanderTarget(city.copy(radius = 1), hall.entrance, worker.id, it) == null } shouldBe true
        repeat(40) { wander.reject(Long.MAX_VALUE) }
        wander.ready(Long.MAX_VALUE - 1) shouldBe false
        wander.ready(Long.MAX_VALUE) shouldBe true
    }

    test("wander finds nearby raised and lowered floors before moving to another horizontal target") {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>()
            val chunk = mockk<Chunk>()
            every { world.isChunkLoaded(any(), any()) } returns true
            every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
            every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            var floorY = 63
            var blockedY: Int? = null
            var liquidY: Int? = null
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
                val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
                val solid = y <= floorY || y == blockedY
                mockk<Block> {
                    every { type } returns if (solid) Material.STONE else Material.AIR
                    every { isPassable } returns !solid
                    every { isLiquid } returns (y == liquidY)
                    every { boundingBox } returns BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(),
                        x + 1.0, y + if (solid) 1.0 else 0.0, z + 1.0)
                }
            }
            val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
            val worker = CivWorker(UUID.randomUUID(), "Idle", null)
            val city = city(listOf(hall), worker, null)
            for (height in listOf(64, 65, 63, 66, 62)) {
                floorY = height - 1
                val wander = CitizenWander()
                var selected: CivPosition? = null
                for (pulse in 0..4) {
                    val now = pulse * 1_000L
                    wander.ready(now) shouldBe true
                    val target = requireNotNull(citizenWanderTarget(city, hall.entrance, worker.id, wander.step))
                    if (citizenStandable(world, target)) { selected = target; break }
                    wander.reject(now)
                }
                selected?.y shouldBe height
            }
            val target = requireNotNull(citizenWanderTarget(city, hall.entrance, worker.id, 0))
            floorY = 63
            blockedY = 64
            citizenStandable(world, target) shouldBe false
            blockedY = 65
            citizenStandable(world, target) shouldBe false
            blockedY = null
            liquidY = 64
            citizenStandable(world, target) shouldBe false
            liquidY = 63
            citizenStandable(world, target) shouldBe false
            liquidY = null
            citizenStandable(world, target) shouldBe true
            every { world.isChunkLoaded(any(), any()) } returns false
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } throws AssertionError("Unloaded terrain must not be read")
            citizenStandable(world, target) shouldBe false
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
        }
    }
})

private fun token(nextDeliveryAt: Long = 0) = CitizenToken(UUID.randomUUID(), 0, nextDeliveryAt, null)
private fun reachWork(cycle: CitizenCycle) { cycle.start(); cycle.arrive(0); cycle.arrive(0) }
private fun building(type: BuildingType, entrance: CivPosition) = CivBuilding(UUID.randomUUID(), type, 1,
    entrance, 0, "test", CivBox(entrance, entrance), entrance, BuildingPhase.ACTIVE)
private fun city(buildings: List<CivBuilding>, worker: CivWorker, order: CivConstruction?) = Settlement(
    UUID.randomUUID(), UUID.randomUUID(), "Test", UUID.randomUUID(), CivPosition(0, 64, 0), 48,
    "test", Stock(), buildings, listOf(worker), construction = order)
