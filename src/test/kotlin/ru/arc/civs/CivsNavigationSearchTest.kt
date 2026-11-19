package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random

class CivsNavigationSearchTest : FunSpec({
    test("a nearby barrel behind a finished perimeter is reached through a distant public gate") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("gate-detour").also(paper.server::addWorld)
            for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
            val start = CivPosition(0, 64, -1)
            val barrel = CivPosition(0, 64, 7)
            world.getBlockAt(barrel.x, barrel.y, barrel.z).type = Material.BARREL
            fun piece(x0: Int, z0: Int, x1: Int, z1: Int, gate: Boolean = false): CivBuilding {
                val point = CivPosition(x0, 64, z0)
                val bounds = CivBox(point, CivPosition(x1, 67, z1))
                if (!gate) for (x in x0..x1) for (z in z0..z1)
                    for (y in 64..67) world.getBlockAt(x, y, z).type = Material.STONE
                return CivBuilding(UUID.randomUUID(), if (gate) BuildingType.GATE else BuildingType.WALL,
                    1, point, 0, "perimeter", bounds, point, BuildingPhase.ACTIVE,
                    transit = if (gate) listOf(point) else null)
            }
            val west = piece(-21, 36, -21, 36, gate = true)
            val east = piece(21, 34, 21, 34, gate = true)
            val north = piece(-21, 0, 21, 0)
            val ring = listOf(north, west, east, piece(-21, 46, 21, 46), piece(-21, 1, -21, 35),
                piece(-21, 37, -21, 45), piece(21, 1, 21, 33), piece(21, 35, 21, 45))
            val city = controlSearchCity(barrel).copy(buildings = ring, construction = null, radius = 80)
            val territory = CivRouteTerritory(city)
            // An opening in the actual ring or a holder within reach across the wall must keep its direct approaches.
            CivRouteTerritory(city.copy(buildings = ring - north)).gateExits(start.point(), barrel, 3.0) shouldBe emptyList()
            territory.gateExits(start.point(), barrel.copy(z = 2), 3.0) shouldBe emptyList()

            fun walk(from: CivPosition, target: CivPosition, food: Boolean): Pair<Boolean, Set<CivPosition>> {
                var at = from.location(world)
                val crossed = mutableSetOf<CivPosition>()
                val tried = mutableSetOf<UUID>()
                val allowed: (CivPosition) -> Boolean = { territory.allows(it, emptySet(), null) }
                repeat(16) {
                    val leg = citizenGateLeg(world, territory, at.point(), target, if (food) 3.0 else 0.0, 96, tried)
                    val pickup = if (food && leg == null) CitizenContainerRouteGoal(target) { p ->
                        civsCanReachContainer(Location(world, p.x, p.y, p.z), target, nearbyFoodPickup = true)
                    } else null
                    val route = CitizenPacketRoute(world, at, (leg?.approach ?: target).location(world), allowed,
                        { _, _ -> true }, { CivsPaths.ORDINARY_COST }, pickup = pickup)
                    route.prepareSnapshot()
                    if (route.failure != null) {
                        if (leg == null) return false to crossed
                        tried += leg.gate
                    } else {
                        var ticks = 0
                        while (!route.finished && route.failure == null && ticks++ < 2_000) {
                            route.move(at, 1.0) { true }?.let { next ->
                                at = next
                                for (gate in listOf(west, east))
                                    if (at.blockX == gate.entrance.x && at.blockZ == gate.entrance.z) crossed += gate.entrance
                            }
                        }
                        route.finished shouldBe true
                        if (leg == null && !route.continuation)
                            return (pickup?.accepts(at.point()) ?: (at.point() == target.point())) to crossed
                    }
                }
                error("Perimeter travel did not terminate at ${at.point()}")
            }
            // The closer eastern gate is physically sealed. Residents use the other crossing in either direction.
            for (y in 64..67) world.getBlockAt(east.entrance.x, y, east.entrance.z).type = Material.STONE
            walk(start, barrel, true) shouldBe (true to setOf(west.entrance))
            walk(start, barrel.copy(x = 3), false) shouldBe (true to setOf(west.entrance))
            walk(barrel.copy(x = 3), start, false) shouldBe (true to setOf(west.entrance))
            for (y in 64..67) world.getBlockAt(west.entrance.x, y, west.entrance.z).type = Material.STONE
            walk(start, barrel, true) shouldBe (false to emptySet())

        }
    }

    test("control broad phase preserves the original ray predicate at corners boundaries and occupied starts") {
        val random = Random(190)
        for (control in listOf(CivPosition(0, 64, 0), CivPosition(-1814, 65, -2762), CivPosition(1226, 73, -3993))) {
            val city = controlSearchCity(control)
            fun point(x: Double, y: Double, z: Double) = CitizenPoint(control.x + x, control.y + y, control.z + z)
            val samples = buildList {
                add(point(.5, 0.0, .5))
                for (edge in listOf(-.3, 1.3)) for (offset in listOf(-.0000001, 0.0, .0000001)) {
                    add(point(edge + offset, 0.0, -2.0)); add(point(edge + offset, 0.0, 2.0))
                    add(point(-2.0, 0.0, edge + offset)); add(point(2.0, 0.0, edge + offset))
                }
                for (height in listOf(-1.8, 1.01)) for (offset in listOf(-.0000001, 0.0, .0000001)) {
                    add(point(-2.0, height + offset, .5)); add(point(2.0, height + offset, .5))
                }
                // The construction's new control must protect its own diagonal and endpoint too.
                add(point(3.0, 0.0, 3.0)); add(point(4.5, 0.0, 4.5)); add(point(6.0, 0.0, 6.0))
            }
            fun compare(from: CitizenPoint, to: CitizenPoint) {
                withClue("control=$control from=$from to=$to") {
                    citizenControlStepClear(city, from, to) shouldBe originalControlStepClear(city, from, to)
                }
            }
            for (from in samples) for (to in samples) compare(from, to)
            repeat(512) {
                fun next() = point(random.nextDouble(-8.0, 8.0), random.nextDouble(-3.0, 3.0), random.nextDouble(-8.0, 8.0))
                compare(next(), next())
            }
        }
    }

    test("repeated geometry probes preserve fractional support collision and hot-exit guards") {
        for (height in listOf(.0625, .5, .9375, 1.0))
            for (kind in listOf("clear", "solid", "reversed", "liquid", "liquid-below", "unknown", "hot", "hot-exit", "body",
                "overlap-exit", "overlap-deep")) {
                val origin = CitizenPoint(.5, 63 + height, .5)
                val target = origin.copy(x = 2.5)
                val avoiding = if (kind == "body") listOf(CitizenTerrainBox(1.1, 63 + height, .1,
                    1.9, 65 + height, .9)) else emptyList()
                val search = CitizenNavigationSearch(origin, target, 0, 256, avoiding)
                var result = search.advance(emptyList())
                var batches = 0
                while (result.probes.isNotEmpty()) {
                    check(batches++ < 32)
                    result = search.advance(result.probes.map { probe ->
                        val blocks = buildMap {
                            for (x in probe.node.x - 1..probe.node.x + 1) for (z in -1..1) for (y in 60..67) {
                                val at = x == (if (kind in listOf("hot-exit", "overlap-exit", "overlap-deep")) 0 else 1) && z == 0 && y == 64
                                val below = kind == "liquid-below" && x == 1 && z == 0 && y == 62
                                val boxes = when {
                                    at && kind in listOf("overlap-exit", "overlap-deep") -> listOf(
                                        CitizenTerrainBox(.1, .05, .1, if (kind == "overlap-exit") .25 else .49, .1, .9))
                                    at && kind == "solid" -> listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
                                    at && kind == "reversed" -> listOf(CitizenTerrainBox(1.0, 1.0, 1.0, 0.0, 0.0, 0.0))
                                    y == 63 -> listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, height, 1.0))
                                    else -> emptyList()
                                }
                                put(CivPosition(x, y, z), CitizenTerrainBlock(!(at && kind == "unknown"),
                                    below || at && kind == "liquid", false, false,
                                    at && kind in listOf("hot", "hot-exit"),
                                    x in 0..2 && z == 0 && y == floor(origin.y + .001).toInt(), 1.0, boxes))
                            }
                        }
                        CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, 0, 256))
                    })
                }
                withClue("height=$height kind=$kind result=$result") {
                    (result.path?.lastOrNull()?.point == target) shouldBe (kind in listOf("clear", "liquid-below", "hot-exit", "overlap-exit"))
                }
            }
    }

    test("prefetched exact neighbourhoods advance the graph without another native request per step") {
        val target = CitizenPoint(3.5, 64.0, .5)
        val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), target, 0, 256, emptyList())
        fun capture(probe: CitizenSearchProbe): CitizenSearchCapture {
            val blocks = buildMap {
                for (x in probe.node.x - 1..probe.node.x + 1) for (z in -1..1) for (y in 61..67)
                    put(CivPosition(x, y, z), CitizenTerrainBlock(true, false, false, false, false,
                        x in 0..3 && z == 0 && y == 64, 1.0,
                        if (y <= 63) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
            }
            return CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, 0, 256))
        }
        val requested = search.advance(emptyList()).probes
        val prefetched = (0..2).map { x -> CitizenSearchProbe(CitizenSearchNode(x, 64.0, 0), CitizenPoint(x + .5, 64.0, .5)) }
        val result = search.advance((requested + prefetched).map(::capture))
        result.failure shouldBe null
        result.probes shouldBe emptyList()
        result.path?.last()?.point shouldBe target
    }

    test("one expansion rejects swept contacts and missing cells without blocking later clear edges") {
        val origin = CitizenPoint(.5, 64.0, .5)
        val air = CitizenTerrainBlock(true, false, false, false, false, true, 1.0, emptyList())
        val blocks = buildMap {
            for (x in -1..1) for (z in -1..1) for (y in 62..67) put(CivPosition(x, y, z), air)
            put(CivPosition(-1, 64, -1), air.copy(climbable = true, boxes = listOf(
                CitizenTerrainBox(.02, .05, .02, .08, .10, .08),
                CitizenTerrainBox(.90, .05, .05, .95, .10, .10),
                // This overhang is captured by the diagonal, outside the north edge's cell range.
                CitizenTerrainBox(1.21, .05, .32, 1.30, .10, .40))))
            // The diagonal contacts this thin post only between its discrete body samples.
            put(CivPosition(-1, 64, 0), air.copy(boxes = listOf(CitizenTerrainBox(.72, .05, .32, .73, .10, .33))))
            put(CivPosition(0, 63, -1), air.copy(liquid = true))
            put(CivPosition(0, 64, -1), air.copy(climbable = true, boxes = listOf(
                // The north edge only touches this face. The east edge does not capture its cell.
                CitizenTerrainBox(.5 + .299, .05, 1.32, .91, .10, 1.40))))
            put(CivPosition(1, 64, 0), air.copy(climbable = true))
            put(CivPosition(0, 63, 1), air.copy(boxes = listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .9, 1.0))))
            put(CivPosition(0, 65, 1), CitizenTerrainBlock.UNAVAILABLE)
            put(CivPosition(1, 64, 1), air.copy(climbable = true))
            put(CivPosition(1, 64, -1), air.copy(boxes = List(3) { i ->
                CitizenTerrainBox(i / 3.0, 0.0, 0.0, (i + 1) / 3.0, .5, 1.0)
            }))
        }
        fun enter(x: Int, z: Int) =
            CitizenSearchEdge(CitizenSearchNode(x, 64.0, z, climbing = true), CitizenRouteAction.LADDER_ENTER, true)
        val north = enter(0, -1)
        val east = enter(1, 0)
        val step = CitizenSearchEdge(CitizenSearchNode(1, 64.5, -1), CitizenRouteAction.STEP, true)
        val search = CitizenNavigationSearch(origin, CitizenPoint(10.5, 64.5, -.5), 0, 256, emptyList())
        val probe = search.advance(emptyList()).probes.single()
        val result = search.advance(listOf(CitizenSearchCapture(probe, blocks,
            listOf(enter(-1, -1), north, east, enter(1, 1), step))))

        result.probes.map(CitizenSearchProbe::node) shouldBe listOf(east.node, step.node, north.node)
        result.nodes shouldBe 4
        result.failure shouldBe null
    }

    test("the configured search batch still limits the local detached frontier") {
        for (batch in listOf(1, 8)) {
            val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), CitizenPoint(8.5, 64.0, .5),
                0, 256, emptyList(), CivsNavigationSettings(searchNodesPerBatch = batch))
            val probe = search.advance(emptyList()).probes.single { !it.incoming }
            val blocks = buildMap {
                for (x in -1..1) for (z in -1..1) for (y in 61..67)
                    put(CivPosition(x, y, z), CitizenTerrainBlock(true, false, false, false, false, y == 64, 1.0,
                        if (y <= 63) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
            }
            val result = search.advance(listOf(CitizenSearchCapture(probe, blocks,
                citizenSearchCandidates(probe, blocks, 0, 256))))
            result.probes.size shouldBe batch
        }
    }

    test("initial long requests group equal scores while local and widened frontiers keep full batches") {
        val blocks = buildMap {
            for (x in -1..2) for (z in -1..4) for (y in 61..67)
                put(CivPosition(x, y, z), CitizenTerrainBlock(true, false, false, false, false, y == 64, 1.0,
                    if (y <= 63) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
        }
        val best = listOf(CitizenSearchNode(1, 64.0, -1), CitizenSearchNode(1, 64.0, 1))
        val costlier = CitizenSearchNode(0, 64.0, 1)
        for (distance in listOf(16, 17)) {
            val gated = distance == 17
            val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), CitizenPoint(distance + .5, 64.0, .5),
                0, 256, emptyList())
            val first = search.advance(emptyList()).probes.single { !it.incoming }
            val result = search.advance(listOf(CitizenSearchCapture(first, blocks,
                (best + costlier).map { CitizenSearchEdge(it, CitizenRouteAction.WALK, true) })))
            result.failure shouldBe null
            result.probes.map(CitizenSearchProbe::node) shouldBe if (gated) best else best + costlier
            result.nodes shouldBe if (gated) 3 else 4
            if (gated) {
                val resumed = search.advance(result.probes.map { CitizenSearchCapture(it, blocks, emptyList()) })
                resumed.probes.map(CitizenSearchProbe::node) shouldBe listOf(costlier)
                resumed.nodes shouldBe 4
                search.cancel()
                search.advance(emptyList()).failure shouldBe "cancelled"
            }
        }
        val detour = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), CitizenPoint(17.5, 64.0, .5),
            0, 256, emptyList(), CivsNavigationSettings(initialHorizontalMargin = 1))
        var probe = detour.advance(emptyList()).probes.single()
        // Two ordinary southward steps leave the valid initial bounds and activate the existing wider phase.
        for (z in 1..2) probe = detour.advance(listOf(CitizenSearchCapture(probe, blocks,
            listOf(CitizenSearchEdge(CitizenSearchNode(0, 64.0, z), CitizenRouteAction.WALK, true))))).probes.single()
        probe.bounds shouldBe null
        val branches = listOf(CitizenSearchNode(1, 64.0, 2), CitizenSearchNode(0, 64.0, 3))
        val widened = detour.advance(listOf(CitizenSearchCapture(probe, blocks,
            branches.map { CitizenSearchEdge(it, CitizenRouteAction.WALK, true) })))
        widened.probes.map(CitizenSearchProbe::node) shouldBe branches
        widened.nodes shouldBe 5
    }

    test("unknown frontier terrain cannot be reported as a proven no-path") {
        for (unknown in listOf(false, true)) {
            val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), CitizenPoint(2.5, 64.0, .5),
                0, 256, emptyList())
            val probe = search.advance(emptyList()).probes.single { !it.incoming }
            val blocks = buildMap {
                for (x in -1..1) for (z in -1..1) for (y in 62..67) {
                    val solid = y <= 63 || x != 0 || z != 0
                    put(CivPosition(x, y, z), CitizenTerrainBlock(true, false, false, false, false, y == 64, 1.0,
                        if (solid) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
                }
                if (unknown) put(CivPosition(1, 64, 0), CitizenTerrainBlock.UNAVAILABLE)
            }
            var result = search.advance(listOf(CitizenSearchCapture(probe, blocks,
                citizenSearchCandidates(probe, blocks, 0, 256))))
            // The initial centred support is a distinct node; it sees the same closed room.
            while (result.probes.isNotEmpty()) result = search.advance(result.probes.map { next ->
                CitizenSearchCapture(next, blocks, citizenSearchCandidates(next, blocks, 0, 256))
            })
            result.failure shouldBe if (unknown) "chunk-unloaded" else "no-path"
            result.path shouldBe null
        }
    }

    test("a finite long search walks validated progress before continuing toward the original goal") {
        val goal = CitizenPoint(90.5, 64.0, .5)
        var origin = CitizenPoint(.5, 64.0, .5)
        var segments = 0
        do {
            val search = CitizenNavigationSearch(origin, goal, 0, 256, emptyList(),
                CivsNavigationSettings(maximumNodes = 64))
            var result = search.advance(emptyList())
            while (result.probes.isNotEmpty()) result = search.advance(result.probes.map { probe ->
                val blocks = buildMap {
                    for (x in probe.node.x - 1..probe.node.x + 1) for (z in -1..1) for (y in 61..67)
                        put(CivPosition(x, y, z), CitizenTerrainBlock(true, false, false, false, false,
                            x in 0..90 && z == 0 && y == 64, 1.0,
                            if (y <= 63) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
                }
                CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, 0, 256))
            })
            result.failure shouldBe null
            val endpoint = requireNotNull(result.path).last().point
            (endpoint.x > origin.x) shouldBe true
            result.path!!.all { it.point.y == 64.0 && it.point.z == .5 } shouldBe true
            if (result.continuation) (goal.x - endpoint.x <= goal.x - origin.x - 2.0) shouldBe true
            origin = endpoint
            segments++
            (segments <= 3) shouldBe true
        } while (result.continuation)
        origin shouldBe goal
        (segments > 1) shouldBe true
    }

    for (distance in listOf(32, 48, 64, 90))
    test("a $distance-block ordinary terrain route finishes within the admitted node budget") {
        val target = CitizenPoint(distance + .5, 64.0, .5)
        val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), target, 0, 256, emptyList())
        var result = search.advance(emptyList())
        while (result.probes.isNotEmpty()) {
            result = search.advance(result.probes.map { probe ->
                val y = floor(probe.node.y).toInt()
                val blocks = buildMap {
                    for (x in probe.node.x - 1..probe.node.x + 1) for (z in probe.node.z - 1..probe.node.z + 1)
                        for (by in y - 2..y + 3) put(CivPosition(x, by, z), CitizenTerrainBlock(
                            true, false, false, false, false, by == 64, CivsPaths.ORDINARY_COST,
                            if (by <= 63) listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()))
                }
                CitizenSearchCapture(probe, blocks, citizenSearchCandidates(probe, blocks, 0, 256))
            })
        }
        withClue("nodes=${result.nodes} failure=${result.failure}") {
            result.failure shouldBe null
            result.path?.last()?.point shouldBe target
            (result.nodes <= CitizenNavigationSearch.MAXIMUM_NODES) shouldBe true
        }
    }

    test("a supported shaft centres before climbing and exits onto its high landing in both directions") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("shaft-climb-exit").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for (x in -1..3) for (z in -1..1) for (y in 64..69) world.getBlockAt(x, y, z).type = Material.STONE
            for (y in 64..68) world.getBlockAt(0, y, 0).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
            for (x in 1..2) for (y in 67..68) world.getBlockAt(x, y, 0).type = Material.AIR
            val bottom = Location(world, .57, 64.0, .55)
            val landing = Location(world, 2.5, 67.0, .5)
            for ((start, target) in listOf(bottom to landing, landing to Location(world, .5, 64.0, .5))) {
                var at = start.clone()
                val route = CitizenPacketRoute(world, start, target,
                    { it.x in 0..2 && it.z == 0 && it.y in 64..67 }, { _, _ -> true }, { 1.0 })
                route.prepareSnapshot()
                route.failure shouldBe null
                var packets = 0
                var climbed = false
                while (!route.finished && route.failure == null && packets++ < 160) {
                    route.move(at, 1.0) { true }?.let { next ->
                        (at.distance(next) <= .215001) shouldBe true
                        if (next.y != at.y) {
                            next.x shouldBe .5
                            next.z shouldBe .5
                            climbed = true
                        }
                        for (sample in 0..10) {
                            val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                            withClue("start=$start from=$at next=$next sample=$sample") {
                                citizenPacketBodyClear(world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                            }
                        }
                        at = next
                    }
                }
                withClue("start=$start target=$target at=$at failure=${route.failure} packets=$packets") {
                    route.failure shouldBe null
                    route.finished shouldBe true
                    climbed shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                }
            }
        }
    }

})

private fun controlSearchCity(control: CivPosition): Settlement {
    fun building(index: Long, at: CivPosition) = CivBuilding(UUID(0, index), BuildingType.TOWN_HALL, 1,
        at, 0, "control-search-test", CivBox(at, at), at, BuildingPhase.ACTIVE, control = at)
    val buildings = listOf(building(1, control)) + (0..63).map { index ->
        building(index + 2L, control.copy(x = control.x + 50 + index * 2, z = control.z + 80))
    } + building(66, control).copy(control = null)
    val planned = building(67, control.copy(x = control.x + 4, z = control.z + 4)).copy(phase = BuildingPhase.CONSTRUCTING)
    return Settlement(UUID(1, 1), UUID(1, 2), "Control search", UUID(1, 3), control, 256, "test", Stock(),
        buildings, emptyList(), construction = CivConstruction(UUID(1, 4), planned.id, 1, 1, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = planned))
}

/** Frozen pre-optimization oracle: keep Bukkit's exact endpoint and ray boundary behavior. */
private fun originalControlStepClear(city: Settlement, from: CitizenPoint, to: CitizenPoint): Boolean {
    val origin = Vector(from.x, from.y, from.z)
    val destination = Vector(to.x, to.y, to.z)
    val delta = destination.clone().subtract(origin)
    val distance = delta.length()
    return civsProtectedBuildings(city).none { building ->
        val p = building.control ?: return@none false
        val obstacle = BoundingBox(p.x - .3, p.y - 1.8, p.z - .3, p.x + 1.3, p.y + 1.01, p.z + 1.3)
        obstacle.contains(destination) || !obstacle.contains(origin) && distance > 0 &&
            obstacle.rayTrace(origin, delta.clone().normalize(), distance) != null
    }
}

private fun CitizenPacketRoute.prepareSnapshot() {
    val limit = CitizenNavigationCapture.MAXIMUM_BLOCKS + CitizenNavigationSearch.MAXIMUM_NODES * 64
    var operations = 0
    while (searching && operations++ < limit) expand()
    withClue("phase=$preparationPhase nodes=$nodeCount captured=$capturedBlocks failure=$failure") {
        searching shouldBe false
    }
}
