package ru.arc.civs

import com.destroystokyo.paper.entity.Pathfinder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.citizensnpcs.api.ai.NavigatorParameters
import net.citizensnpcs.api.ai.event.CancelReason
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.SimpleMetadataStore
import org.bukkit.Location
import java.util.UUID

class CivsCitizensTest : FunSpec({
    test("native activation follows each NPC viewer range without saving it or retaining chunks") {
        val unrelatedNpc = SimpleMetadataStore()
        for (range in listOf(1, 32, 64, 96)) {
            val metadata = SimpleMetadataStore()
            metadata.set(NPC.Metadata.NAMEPLATE_VISIBLE, true)
            configureCitizenActivity(metadata, range)

            metadata.get<Int>(NPC.Metadata.ACTIVATION_RANGE) shouldBe range
            metadata.get<Boolean>(NPC.Metadata.KEEP_CHUNK_LOADED) shouldBe false
            metadata.get<Boolean>(NPC.Metadata.SHOULD_SAVE) shouldBe false
            metadata.get<Boolean>(NPC.Metadata.NAMEPLATE_VISIBLE) shouldBe true
        }
        unrelatedNpc.has(NPC.Metadata.ACTIVATION_RANGE) shouldBe false
    }

    test("unbounded or always-active Citizens ranges are rejected before changing NPC metadata") {
        for (range in listOf(-1, 0, 97, Int.MAX_VALUE)) {
            val metadata = SimpleMetadataStore()
            shouldThrow<IllegalArgumentException> { configureCitizenActivity(metadata, range) }
            metadata.size() shouldBe 0
        }
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

    test("exact native routing plans once at first update and keeps Citizens in control until native completion") {
        val target = Location(null, 15.5, 1.0, -11.5)
        val pathfinder = mockk<Pathfinder>()
        val path = mockk<Pathfinder.PathResult>()
        every { pathfinder.findPath(any<Location>()) } returns path
        every { path.finalPoint } returns Location(null, 15.0, 1.0, -12.0)
        every { path.canReachFinalPoint() } returns true
        every { pathfinder.moveTo(path, 1.0) } returns true
        every { pathfinder.hasPath() } returns true
        every { pathfinder.stopPathfinding() } returns Unit
        val route = CitizenExactNavigation(pathfinder, target, NavigatorParameters().speedModifier(1f))

        verify(exactly = 0) { pathfinder.findPath(any<Location>()) }
        route.update() shouldBe false
        route.update() shouldBe false
        verify(exactly = 1) { pathfinder.findPath(target) }
        verify(exactly = 1) { pathfinder.moveTo(path, 1.0) }
        every { pathfinder.hasPath() } returns false
        route.update() shouldBe true
        route.cancelReason shouldBe null
        route.stop()
        route.stop()
        route.update() shouldBe true
        verify(exactly = 1) { pathfinder.stopPathfinding() }
        verify(exactly = 1) { pathfinder.findPath(any<Location>()) }
    }

    test("native partial paths and unreachable target cells never start movement") {
        val target = Location(null, 15.5, 1.0, -11.5)
        for ((end, reachable) in listOf(
            Location(null, 14.0, 1.0, -12.0) to true,
            Location(null, 15.0, 2.0, -12.0) to true,
            Location(null, 15.0, 1.0, -12.0) to false,
            null to true,
        )) {
            val pathfinder = mockk<Pathfinder>()
            val path = mockk<Pathfinder.PathResult>()
            every { pathfinder.findPath(any<Location>()) } returns path
            every { path.finalPoint } returns end
            every { path.canReachFinalPoint() } returns reachable
            val route = CitizenExactNavigation(pathfinder, target, NavigatorParameters())
            route.update() shouldBe true
            route.cancelReason shouldBe CancelReason.STUCK
            verify(exactly = 0) { pathfinder.moveTo(any<Pathfinder.PathResult>(), any()) }
        }
    }

    test("native planning and movement rejection fail the route and cancellation cannot restart it") {
        val target = Location(null, 15.5, 1.0, -11.5)
        val pathfinder = mockk<Pathfinder>()
        every { pathfinder.findPath(any<Location>()) } returns null
        every { pathfinder.stopPathfinding() } returns Unit
        val missing = CitizenExactNavigation(pathfinder, target, NavigatorParameters())
        missing.update() shouldBe true
        missing.cancelReason shouldBe CancelReason.STUCK
        missing.stop()
        missing.update() shouldBe true
        verify(exactly = 1) { pathfinder.findPath(any<Location>()) }

        val path = mockk<Pathfinder.PathResult>()
        every { pathfinder.findPath(any<Location>()) } returns path
        every { path.finalPoint } returns Location(null, 15.0, 1.0, -12.0)
        every { path.canReachFinalPoint() } returns true
        every { pathfinder.moveTo(path, 1.0) } returns false
        val rejected = CitizenExactNavigation(pathfinder, target, NavigatorParameters().speedModifier(1f))
        rejected.update() shouldBe true
        rejected.cancelReason shouldBe CancelReason.STUCK
        verify(exactly = 0) { pathfinder.hasPath() }
        val cancelled = CitizenExactNavigation(pathfinder, target, NavigatorParameters())
        cancelled.stop()
        cancelled.update() shouldBe true
        verify(exactly = 2) { pathfinder.findPath(any<Location>()) }
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
        citizenPlan(city.copy(construction = order.copy(phase = ConstructionPhase.PLACING)), worker)?.idleReason shouldBe "construction-paused"
        citizenPlan(city, worker.copy(assignment = null))?.idleReason shouldBe "unassigned"
    }
})

private fun token(nextDeliveryAt: Long = 0) = CitizenToken(UUID.randomUUID(), 0, nextDeliveryAt, null)
private fun reachWork(cycle: CitizenCycle) { cycle.start(); cycle.arrive(0); cycle.arrive(0) }
private fun building(type: BuildingType, entrance: CivPosition) = CivBuilding(UUID.randomUUID(), type, 1,
    entrance, 0, "test", CivBox(entrance, entrance), entrance, BuildingPhase.ACTIVE)
private fun city(buildings: List<CivBuilding>, worker: CivWorker, order: CivConstruction?) = Settlement(
    UUID.randomUUID(), UUID.randomUUID(), "Test", UUID.randomUUID(), CivPosition(0, 64, 0), 48,
    "test", Stock(), buildings, listOf(worker), construction = order)
