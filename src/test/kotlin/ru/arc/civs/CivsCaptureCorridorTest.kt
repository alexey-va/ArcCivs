package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.max
import kotlin.math.min

class CivsCaptureCorridorTest : FunSpec({
    test("default native prefetch keeps probe centres inside the worker's initial corridor") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("default-native-prefetch").also(paper.server::addWorld)
            for (x in -2..3) for (z in -2..2) world.loadChunk(x, z)
            val from = Location(world, .35232563679, 64.0, .64767436321)
            val target = Location(world, 40.5, 64.0, .5)
            val settings = CivsNavigationSettings()
            val route = CitizenPacketRoute(world, from, target,
                { it.y == 64 && it.x in -16..56 && it.z in -16..16 }, { _, _ -> true }, { 1.0 }, settings = settings)
            val search = route.newSearch()
            val terrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
            val initial = search.advance(emptyList())
            initial.failure shouldBe null
            (initial.probes.isNotEmpty()) shouldBe true
            val first = captureCorridorBatch(CitizenNavigationCapture(route, initial.probes, terrain))
            val next = search.advance(first)
            next.failure shouldBe null
            (next.probes.isNotEmpty()) shouldBe true
            val captured = captureCorridorBatch(CitizenNavigationCapture(route, next.probes, terrain))
            // This is a real speculative frontier, not just the requested worker probes.
            (captured.size > next.probes.size) shouldBe true
            val outside = captured.map { it.probe.node.point() }.filterNot {
                it.x in min(from.x, target.x) - settings.initialHorizontalMargin..max(from.x, target.x) + settings.initialHorizontalMargin &&
                    it.z in min(from.z, target.z) - settings.initialHorizontalMargin..max(from.z, target.z) + settings.initialHorizontalMargin &&
                    it.y in min(from.y, target.y) - settings.initialVerticalMargin..max(from.y, target.y) + settings.initialVerticalMargin
            }
            withClue("requested=${next.probes.size} captured=${captured.size} outside=${outside.take(8)}") {
                outside shouldBe emptyList()
            }
            (route.capturedBlocks <= settings.maximumCapturedBlocks) shouldBe true
        }
    }

    test("a wider search reuses boundary edges to walk around a wall outside the initial corridor") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("default-native-wide-detour").also(paper.server::addWorld)
            for (x in -1..3) for (z in -1..1) world.loadChunk(x, z)
            for (z in -6..6) for (y in 64..67) world.getBlockAt(20, y, z).type = Material.STONE
            val from = Location(world, .5, 64.0, .5)
            val target = Location(world, 40.5, 64.0, .5)
            val settings = CivsNavigationSettings()
            val route = CitizenPacketRoute(world, from, target,
                { it.y == 64 && it.x in -2..42 && it.z in -8..8 }, { _, _ -> true }, { 1.0 }, settings = settings)
            val maximumOperations = settings.maximumCapturedBlocks + settings.maximumNodes * 64
            var operations = 0
            while (route.searching && operations++ < maximumOperations) route.expand()
            withClue("phase=${route.preparationPhase} nodes=${route.nodeCount} blocks=${route.capturedBlocks}") {
                route.searching shouldBe false
                route.failure shouldBe null
                route.continuation shouldBe false
                route.endpoint shouldBe target.point()
                (route.nodeCount <= settings.maximumNodes + 1) shouldBe true
                (route.capturedBlocks <= settings.maximumCapturedBlocks) shouldBe true
            }
            var at = from
            var usedWiderOpening = false
            for (tick in 0 until 1_200) {
                if (route.finished) break
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketSupported(world, next.point()) shouldBe true
                    citizenPacketBodyClear(world, next.point(), false) shouldBe true
                    citizenPacketStepClear(world, at.point(), next.point(), false) shouldBe true
                    if (next.z < min(from.z, target.z) - settings.initialHorizontalMargin ||
                        next.z > max(from.z, target.z) + settings.initialHorizontalMargin) usedWiderOpening = true
                    at = next
                }
            }
            usedWiderOpening shouldBe true
            route.finished shouldBe true
            route.failure shouldBe null
            at.point() shouldBe target.point()
            for (z in -6..6) for (y in 64..67) world.getBlockAt(20, y, z).type shouldBe Material.STONE
        }
    }
})

private fun captureCorridorBatch(cursor: CitizenNavigationCapture): List<CitizenSearchCapture> {
    val maximumOperations = CitizenNavigationCapture.MAXIMUM_BLOCKS + CivsNavigationSettings().searchNodesPerBatch * 128
    repeat(maximumOperations) {
        if (cursor.step()) return cursor.result
    }
    error("Native capture did not finish within $maximumOperations operations")
}
