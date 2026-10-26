package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.floor

class CivsNavigationSearchTest : FunSpec({
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

    test("the recorded lower shaft has no landing exit inside its captured headroom") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("recorded-mine-landing").also(paper.server::addWorld)
            for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
            val materials = listOf(Material.AIR, Material.COAL_ORE, Material.COBBLESTONE, Material.COPPER_ORE,
                Material.DIRT, Material.GRANITE, Material.GRASS_BLOCK, Material.GRAVEL, Material.LADDER,
                Material.STONE, Material.TORCH)
            for ((dy, layer) in RECORDED_LANDING.withIndex()) for ((dz, row) in layer.split(' ').withIndex())
                for ((dx, value) in row.withIndex()) {
                    val block = world.getBlockAt(-98 + dx, 55 + dy, -52 + dz)
                    block.type = materials[value - 'A']
                    if (block.type == Material.LADDER)
                        block.blockData = Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                }
            val ladder = Location(world, -92.5, 60.0, -46.5)
            val landing = Location(world, -95.5, 60.0, -50.5)
            // The ladder continues through the snapshot's upper edge at y=63. A body above
            // feet y=62 would require uncaptured y=64+; this fixture cannot assume that exit.
            citizenPacketBodyClear(world, CitizenPoint(ladder.x, ladder.y, ladder.z), false) shouldBe true
            val route = CitizenPacketRoute(world, ladder, landing,
                { it.x in -98..-90 && it.z in -52..-44 && it.y in 55..62 }, { _, _ -> true }, { 1.0 })
            route.prepareSnapshot()
            route.failure shouldBe "no-path"
            route.endpoint shouldBe null
            route.move(ladder, 1.0) { true } shouldBe null

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

private fun CitizenPacketRoute.prepareSnapshot() {
    val limit = CitizenNavigationCapture.MAXIMUM_BLOCKS + CitizenNavigationSearch.MAXIMUM_NODES * 64
    var operations = 0
    while (searching && operations++ < limit) expand()
    withClue("phase=$preparationPhase nodes=$nodeCount captured=$capturedBlocks failure=$failure") {
        searching shouldBe false
    }
}

/**
 * Exact material cells from native block-scan at 2026-10-22T11:52:00.196Z, x=-98..-90,
 * y=55..63, z=-52..-44. A..K is the material palette above; every ladder faces south.
 * This historical snapshot predates the .47 timeout logs. Its bounded lower shaft has no
 * landing exit without uncaptured headroom; it says nothing about the current server's
 * complete route and does not pretend the later off-centre pose is clear.
 */
private val RECORDED_LANDING = listOf(
    "JJJJJJJJJ JJJJJJJJJ EEJJJJEJJ EEEEEEEJJ AGGEEEEEE AAAAAGEEE AAAGEEEEE AAJEEEEEE JJJJJJJJJ",
    "JJJJJJJJJ EEJJJJEEE EEEEEEEEJ GGEEEEEEJ AAAECECEE AAAACACEE AAAACCCEE AAJECCEEE JJJJCCJJJ",
    "EEEEJJEEE EEEEEEEEE GGEEEEEEJ AAGGEGEEJ AAACACAJJ AAACAIKEE AAACAAAEE JAJJKAJEE JJJJAAJJJ",
    "EEEEEEEEE GGEEEEEEE AAGGGGGEJ AAAACAEGJ AAACACAJJ AAACAIAJJ AAACAAAJJ JJJJAAJJJ JJJJAAJJJ",
    "GGGGEEEEE AAGGGGGGG AAAAAAAGJ AAAACACAJ AAACACAJJ AAACAIAJJ JJACAAAJJ JJJJAAJJJ JJJJAAJJJ",
    "AAAAGGGGG AAAAAAAAA AAAAAAAAJ AAAAAAAJJ AAAACCJJJ JAAACIJJJ JJJAJJJJJ JJJJJJJJJ JJJJJJJJJ",
    "AAAAAAAAA AAAAAAAAA AAAAAAAJJ AAAAAAJJJ AAAAACJJJ FJJACIJJJ JJJJJJJJJ JJJJJJJJJ JJJJJJJJJ",
    "FAAAAAAAA AAAAAAAAH AAAAAAAJJ FAAACAJJJ FFAAACJJJ FFJJJIJJJ FJJJJBJJJ JJJJJJJJJ JJJJJJJJJ",
    "FAAAAAAAH AAAAAAAHH FAAAAADJH FFFEEAEEJ FFEEECEEE EEFJJIJJJ EEJJJJJJJ EEJJJBBJJ EEJJJBBJJ",
)
