package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import kotlin.math.sqrt

class CivsPartialNavigationTest : FunSpec({
    for (distance in listOf(2, 16, 48))
    test("an unreachable $distance-block goal walks only its validated prefix and stops retrying at the frontier") {
        val origin = CitizenPoint(.5, 64.0, .5)
        val target = CitizenPoint(distance + .5, 64.0, .5)
        val wall = if (distance == 2) 2 else 4
        val result = partialSearch(origin, target, wall)
        result.failure shouldBe null
        result.continuation shouldBe true
        result.continuationReason shouldBe "no-path"
        val path = requireNotNull(result.path)
        path.last().point shouldBe CitizenPoint(wall - .5, 64.0, .5)
        path.all { it.point.x < wall && it.point.y == 64.0 && it.point.z == .5 } shouldBe true
        (sqrt(origin.distanceSquared(target)) - sqrt(path.last().point.distanceSquared(target)) >= .5) shouldBe true
        result.blockedPositions.isNotEmpty() shouldBe true

        val stopped = partialSearch(path.last().point, target, wall)
        stopped.failure shouldBe "no-path"
        stopped.path shouldBe null
        stopped.continuation shouldBe false
        stopped.continuationReason shouldBe null
    }

    test("a proven isolated nearby goal keeps a safe prefix without claiming its protected endpoint") {
        val target = CitizenPoint(4.5, 64.0, .5)
        val result = partialSearch(CitizenPoint(.5, 64.0, .5), target, wall = null, allowed = { it.x < 4 })
        result.failure shouldBe null
        result.continuationReason shouldBe "no-path"
        requireNotNull(result.path).last().point shouldBe CitizenPoint(3.5, 64.0, .5)
        val stopped = partialSearch(CitizenPoint(3.5, 64.0, .5), target, wall = null, allowed = { it.x < 4 })
        stopped.failure shouldBe "no-path"
        stopped.path shouldBe null
    }

    test("a proven isolated goal retains its no-path reason when an unchanged capture limit stops the frontier") {
        val target = CitizenPoint(4.5, 64.0, .5)
        val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), target, 0, 256, emptyList(),
            CivsNavigationSettings(searchNodesPerBatch = 1, maximumNodes = 64, maximumCapturedBlocks = 256))
        val first = search.advance(emptyList())
        search.advance(first.probes.map { partialCapture(it, target, wall = null, allowed = { point -> point.x < 4 }) })
        val result = search.finishAtBudget("capture-budget")
        result.continuationReason shouldBe "no-path"
        requireNotNull(result.path).last().point shouldBe CitizenPoint(1.5, 64.0, .5)
        (result.nodes <= 64) shouldBe true
    }

    test("unknown native terrain and cancellation cannot return a discovered prefix") {
        val target = CitizenPoint(8.5, 64.0, .5)
        val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), target, 0, 256, emptyList())
        val unknown = partialFinish(search, target, wall = 4, unknownX = 3)
        unknown.failure shouldBe "chunk-unloaded"
        unknown.path shouldBe null
        unknown.continuation shouldBe false
        search.finishAtBudget("capture-budget").failure shouldBe "chunk-unloaded"
        search.cancel()
        val cancelled = search.finishAtBudget("capture-budget")
        cancelled.failure shouldBe "cancelled"
        cancelled.path shouldBe null
    }

    test("a close capture budget keeps real progress without raising the node or capture limits") {
        val target = CitizenPoint(16.5, 64.0, .5)
        val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), target, 0, 256, emptyList(),
            CivsNavigationSettings(searchNodesPerBatch = 1, maximumNodes = 64, maximumCapturedBlocks = 256))
        val first = search.advance(emptyList())
        search.advance(first.probes.map { partialCapture(it, target, wall = null) })
        val result = search.finishAtBudget("capture-budget")
        result.failure shouldBe null
        result.continuationReason shouldBe "capture-budget"
        requireNotNull(result.path).last().point shouldBe CitizenPoint(1.5, 64.0, .5)
        (result.nodes <= 64) shouldBe true
    }

    test("a native partial walk cannot earn work or delivery and cannot replay from the same point") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("partial-native-route").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for (y in 64..65) world.getBlockAt(4, y, 0).type = Material.STONE
            val target = Location(world, 8.5, 64.0, .5)
            var at = Location(world, .5, 64.0, .5, -90f, 0f)
            fun route() = CitizenPacketRoute(world, at, target,
                { it.x in 0..8 && it.y == 64 && it.z == 0 }, { _, _ -> true }, { 1.0 })
            val cycle = CitizenCycle(CitizenToken(UUID(0, 7), 23, 0, null), 6_000)
            cycle.start()
            cycle.arrive(0)
            cycle.phase shouldBe CitizenPhase.TO_WORK
            val first = route()
            repeat(1_000) { if (first.searching) first.expand() }
            first.searching shouldBe false
            first.continuationReason shouldBe "no-path"
            repeat(100) { first.move(at, 1.0) { true }?.let { next ->
                citizenPacketStepClear(world, at.point(), next.point(), false) shouldBe true
                at = next
            } }
            first.finished shouldBe true
            first.failure shouldBe null
            at.point() shouldBe CitizenPoint(3.5, 64.0, .5)
            (first.forwardProgress >= 3.0 - .000001) shouldBe true
            citizenNavigationOutcome(CitizenNavigationCompletion(null), false, 6_000, 10_000, false) shouldBe
                CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
            cycle.work(60_000)
            cycle.phase shouldBe CitizenPhase.TO_WORK
            cycle.observedWorkMillis shouldBe 0L
            cycle.beginDelivery(60_000) shouldBe null
            val stopped = route()
            repeat(1_000) { if (stopped.searching) stopped.expand() }
            stopped.failure shouldBe "no-path"
            stopped.continuation shouldBe false
            stopped.move(at, 1.0) { true } shouldBe null
        }
    }

    test("an externally displaced partial endpoint cannot fabricate forward walking progress") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("partial-no-progress").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val from = Location(world, .5, 64.0, .5, -90f, 0f)
            val route = CitizenPacketRoute(world, from, Location(world, 8.5, 64.0, .5),
                { it.y == 64 && it.z == 0 }, { _, _ -> true }, { 1.0 })
            route.acceptSearch(CitizenSearchResult(emptyList(), 1,
                path = listOf(CitizenPlannedStep(CitizenPoint(1.5, 64.0, .5), CitizenRouteAction.WALK)),
                continuation = true, continuationReason = "no-path"))
            route.move(Location(world, 1.5, 64.0, .5, -90f, 0f), 1.0) { true } shouldBe null
            route.failure shouldBe "no-progress"
            route.forwardProgress shouldBe 0.0
        }
    }
})

private fun partialSearch(origin: CitizenPoint, target: CitizenPoint, wall: Int?,
    allowed: (CivPosition) -> Boolean = { true }): CitizenSearchResult = partialFinish(
    CitizenNavigationSearch(origin, target, 0, 256, emptyList()), target, wall, allowed = allowed)

private fun partialFinish(search: CitizenNavigationSearch, target: CitizenPoint, wall: Int?, unknownX: Int? = null,
    allowed: (CivPosition) -> Boolean = { true }): CitizenSearchResult {
    var result = search.advance(emptyList())
    repeat(200) {
        if (result.probes.isEmpty()) return result
        result = search.advance(result.probes.map { partialCapture(it, target, wall, unknownX, allowed) })
    }
    error("Partial search did not finish")
}

private fun partialCapture(probe: CitizenSearchProbe, target: CitizenPoint, wall: Int?, unknownX: Int? = null,
    allowed: (CivPosition) -> Boolean = { true }): CitizenSearchCapture {
    val blocks = buildMap {
        for (x in probe.node.x - 1..probe.node.x + 1) for (z in -1..1) for (y in 61..67) {
            val p = CivPosition(x, y, z)
            val solid = y <= 63 || x == wall && y <= 65
            put(p, if (x == unknownX && y == 64 && z == 0) CitizenTerrainBlock.UNAVAILABLE else
                CitizenTerrainBlock(true, false, false, false, false,
                    x in 0..target.x.toInt() && y == 64 && z == 0 && allowed(p), 1.0,
                    if (solid) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
        }
    }
    return CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, 0, 256))
}
