package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.floor

class CivsRoadRoutingTest : FunSpec({
    test("a sparse paid detour reaches the same goal with fewer native cells probes and search nodes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val fixture = RoadRoutingFixture()
            val ordinary = fixture.run(false)
            val road = fixture.run(true)
            println("road-routing-detour ordinary=$ordinary road=$road")
            withClue("ordinary=$ordinary road=$road") {
                ordinary.failure shouldBe null
                ordinary.continuation shouldBe false
                ordinary.path.last().point shouldBe fixture.target
                road.failure shouldBe null
                road.continuation shouldBe false
                road.path.last().point shouldBe fixture.target
                road.mode shouldBe CitizenRoadRouting.ROAD
                (road.nodes < ordinary.nodes) shouldBe true
                (road.cells < ordinary.cells) shouldBe true
                (road.probes < ordinary.probes) shouldBe true
                road.path.any { it.point.z > 5.0 } shouldBe true
                fixture.run(true) shouldBe road
            }
        }
    }

    test("absent or disconnected road metadata keeps the ordinary search unchanged") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
            val fixture = RoadRoutingFixture()
            val ordinary = fixture.run(false)
            for (supports in listOf(emptySet(), fixture.pavement.filterNot { it.x in 15..17 }.toSet())) {
                val withoutRoute = fixture.run(true, supports = supports)
                withoutRoute shouldBe ordinary
            }
        }
    }

    for (obstruction in listOf("block", "policy", "unloaded-chunk"))
        test("a retained road with a new $obstruction obstruction falls back and still obeys native and directed guards") {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use {
                val fixture = RoadRoutingFixture(obstruction)
                val ordinary = fixture.run(false)
                val result = fixture.run(true)
                println("road-routing-$obstruction ordinary=$ordinary road=$result")
                withClue("ordinary=$ordinary road=$result") {
                    ordinary.failure shouldBe null
                    ordinary.continuation shouldBe false
                    ordinary.path.last().point shouldBe fixture.target
                    result.failure shouldBe null
                    result.continuation shouldBe false
                    result.mode shouldBe CitizenRoadRouting.FALLBACK
                    result.path.last().point shouldBe fixture.target
                    result.path.none { floor(it.point.x).toInt() == 16 && floor(it.point.z).toInt() == 6 } shouldBe true
                    if (obstruction == "unloaded-chunk") result.path.none {
                        floor(it.point.x).toInt() shr 4 == 1 && floor(it.point.z).toInt() shr 4 == 0
                    } shouldBe true
                    (result.nodes <= CivsNavigationSettings().maximumNodes) shouldBe true
                    (result.cells <= CivsNavigationSettings().maximumCapturedBlocks) shouldBe true
                }
            }
        }

    test("learned corridors participate without a paid road and local invalidation removes their broken link") {
        val city = pathsCity()
        val paths = CivsPaths()
        val trip = paths.begin(city)
        val points = roadRoutingPavement()
        points.forEach { trip.observe(it.copy(y = it.y + 1).point()) }
        paths.complete(city, trip)
        repeat(4) { paths.pulse() }
        val from = CitizenPoint(.5, 64.0, .5)
        val to = CitizenPoint(32.5, 64.0, .5)
        val admitted = paths.begin(city).weights
        (admitted.preferred === paths.begin(city).weights.preferred) shouldBe true
        (admitted.roads.corridor(from, to) != null) shouldBe true
        paths.invalidateBlock(city.worldId, CivPosition(16, 64, 6))
        paths.begin(city).weights.roads.corridor(from, to) shouldBe null
        // An active detached search retains its immutable admitted costs; native checks own any obstruction.
        (admitted.roads.corridor(from, to) != null) shouldBe true
    }

    test("paid and learned supports connect across a single grade but separate stacked roads remain disconnected") {
        val from = CitizenPoint(-11.5, 64.0, .5)
        val paid = CivsRoadGraph.Supports((-12..-1).mapTo(hashSetOf()) { CivPosition(it, 63, 0) })
        fun gradedRoad(y: Int): CitizenSearchCorridor? {
            val learned = (0..12).flatMapTo(hashSetOf()) { listOf(CivPosition(it, y, 0), CivPosition(it, y + 20, 0)) }
            return CivsRoadGraph(paid, CivsRoadGraph.Supports(learned))
                .corridor(from, CitizenPoint(12.5, y + 1.0, .5))
        }
        (gradedRoad(64) != null) shouldBe true
        gradedRoad(65) shouldBe null
    }

    test("the direct owner driver cannot invoke a road lookup") {
        sharedRouteRuntime { h ->
            val policy = CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true },
                roadCorridor = { _, _ -> error("Main attempted to build a road corridor") })
            val route = CitizenPacketRoute(h.world, Location(h.world, .5, 64.0, .5),
                Location(h.world, 8.5, 64.0, .5), { true }, { _, _ -> true }, { 1.0 }, snapshotPolicy = policy)
            route.expand()
            route.preparationPhase shouldBe "capturing"
        }
    }

    test("exact and suffix replay win before the sparse graph is evaluated") {
        sharedRouteRuntime { h ->
            var lookups = 0
            var lookupThread: Thread? = null
            val lookup: CitizenRoadLookup = { _, _ -> lookups++; lookupThread = Thread.currentThread(); null }
            val first = h.request(roadCorridor = lookup)
            lookups shouldBe 0
            val worker = Thread(h::runNextWorker, "road-lookup-worker").apply { start(); join() }
            lookupThread shouldBe worker
            h.finish(first)
            lookups shouldBe 1
            h.walk(first)
            val exact = h.request(roadCorridor = lookup)
            h.finish(exact)
            exact.nodeCount shouldBe 0
            exact.capturedBlocks shouldBe 0
            exact.roadRouting shouldBe null
            lookups shouldBe 1
            val suffix = h.request(from = CitizenPoint(3.5, 64.0, .5), roadCorridor = lookup)
            h.finish(suffix)
            suffix.nodeCount shouldBe 0
            suffix.capturedBlocks shouldBe 0
            suffix.roadRouting shouldBe null
            lookups shouldBe 1
            h.routeCounters()["exact_hits"] shouldBe 1L
            h.routeCounters()["suffix_hits"] shouldBe 1L
        }
    }
})

private data class RoadRoutingOutcome(val nodes: Int, val cells: Int, val probes: Int,
    val path: List<CitizenPlannedStep>, val failure: String?, val continuation: Boolean, val mode: CitizenRoadRouting?) {
    override fun toString() = "nodes=$nodes cells=$cells probes=$probes steps=${path.size} " +
        "endpoint=${path.lastOrNull()?.point} failure=$failure continuation=$continuation mode=$mode"
}

/** Identical predecoded terrain, policy and costs in both arms; only sparse guidance is toggled. */
private class RoadRoutingFixture(private val obstruction: String? = null) {
    val pavement = roadRoutingPavement()
    private val origin = CitizenPoint(.5, 64.0, .5)
    val target = CitizenPoint(32.5, 64.0, .5)
    private val air = Bukkit.createBlockData(Material.AIR)
    private val stone = Bukkit.createBlockData(Material.STONE)
    private val states = mapOf(
        air to CitizenSnapshotState(CitizenTerrainShape(false, false, false, false, emptyList()), false),
        stone to CitizenSnapshotState(CitizenTerrainShape(false, false, false, false,
            listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))), false),
    )
    private val palette = arrayOf(air, stone).map { data ->
        CitizenSnapshotChunk.Passability(data).also { it.state(states) }
    }.toTypedArray()
    private val sections = buildList {
        for (x in -1..2) for (z in -1..0) for (y in 3..4) {
            val key = CitizenSnapshotSectionKey(x, y, z)
            val cells = ShortArray(4096) { index ->
                val by = y * 16 + (index shr 8)
                val bx = x * 16 + (index and 15)
                val bz = z * 16 + ((index shr 4) and 15)
                if (by <= 63 || bx == 16 && by <= 66 &&
                    (bz in -4..4 || obstruction == "block" && bz == 6)) 1 else 0
            }
            add(CitizenSnapshotSection(key, 1, 0, cells, palette,
                mapOf(air to CivPosition(x * 16, y * 16, z * 16), stone to CivPosition(x * 16, y * 16, z * 16))))
        }
    }
    private val policy = CitizenSnapshotPolicy(
        { point, _ -> point.y == 64 && point.x in -2..34 && point.z in -8..8 },
        { _, to -> obstruction != "policy" || floor(to.x).toInt() != 16 || floor(to.z).toInt() != 6 },
        supportCost = { point, _ -> if (point in pavement) 1.0 else CivsPaths.ORDINARY_COST },
    )

    private val city = pathsCity()
    private fun graph(supports: Set<CivPosition>): CivsRoadGraph {
        val roads = CivsPaths.RetainedRoads.from(city, CivAutonomousAccess(
            mapOf(city.buildings.single().id to supports.map { it.copy(y = it.y + 1) })))
        return CivsPaths().begin(city, roads = roads).weights.roads
    }
    private val streets = graph(pavement)

    fun run(preferRoad: Boolean, supports: Set<CivPosition> = pavement): RoadRoutingOutcome {
        val graph = if (supports === pavement) streets else graph(supports)
        val terrain = CitizenSnapshotTerrain().also {
            it.seed(sections)
            it.states.putAll(states)
            if (obstruction == "unloaded-chunk") it.unavailable(setOf(CitizenSnapshotKey(1, 0)))
        }
        val search = CitizenNavigationSearch(origin, target, 0, 256, emptyList(),
            roadCorridor = if (preferRoad) graph::corridor else null)
        var result = search.advance(emptyList())
        var probes = 0
        var batches = 0
        while (result.probes.isNotEmpty()) {
            check(batches++ < 256) { "Road search did not finish" }
            val capture = CitizenSnapshotCapture(result.probes, terrain, policy, 0, 256, 32768, 256, true)
            val request = capture.advance(CitizenSnapshotInput(), search::captured)
            val captures = checkNotNull(request.captures) { "Predecoded road fixture requested native input: $request" }
            probes += captures.size
            result = search.advance(captures)
        }
        return RoadRoutingOutcome(result.nodes, terrain.blocks.size, probes,
            result.path.orEmpty(), result.failure, result.continuation, result.roadRouting)
    }
}

private fun roadRoutingPavement(): Set<CivPosition> = buildSet {
    for (z in 0..6) add(CivPosition(0, 63, z))
    for (x in 1..32) add(CivPosition(x, 63, 6))
    for (z in 5 downTo 0) add(CivPosition(32, 63, z))
}
