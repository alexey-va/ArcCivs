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
            for (kind in listOf("clear", "solid", "reversed", "liquid", "liquid-below", "unknown", "hot", "hot-exit", "body")) {
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
                                val at = x == (if (kind == "hot-exit") 0 else 1) && z == 0 && y == 64
                                val below = kind == "liquid-below" && x == 1 && z == 0 && y == 62
                                val boxes = when {
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
                    (result.path?.lastOrNull()?.point == target) shouldBe (kind in listOf("clear", "liquid-below", "hot-exit"))
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

    test("the configured search batch limits detached frontier requests") {
        for (batch in listOf(1, 8)) {
            val search = CitizenNavigationSearch(CitizenPoint(.5, 64.0, .5), CitizenPoint(90.5, 64.0, .5),
                0, 256, emptyList(), CivsNavigationSettings(searchNodesPerBatch = batch))
            val probe = search.advance(emptyList()).probes.single()
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
