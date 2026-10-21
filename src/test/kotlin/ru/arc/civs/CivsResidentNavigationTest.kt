package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.Lightable
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID

class CivsResidentNavigationTest : FunSpec({
    test("packet movement walks around an occupied body-width corner and rejects an inserted wall") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (z in -2..2) for (y in 64..65) terrain.blocks[CivPosition(2, y, z)] = Material.STONE
            val route = terrain.route()
            repeat(20_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            route.searching shouldBe false
            var at = Location(terrain.world, .5, 64.0, .5)
            var detoured = false
            repeat(200) {
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketBodyClear(terrain.world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    if (kotlin.math.abs(next.z - .5) >= 2.5) detoured = true
                    at = next
                }
            }
            route.finished shouldBe true
            detoured shouldBe true
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0

            val changed = ResidentRouteTerrain()
            val second = changed.route()
            repeat(2_000) { if (second.searching) second.expand() }
            val next = requireNotNull(second.destination)
            changed.blocks[CivPosition(next.blockX, 64, next.blockZ)] = Material.STONE
            second.move(Location(changed.world, .5, 64.0, .5), 1.0) { true } shouldBe null
            second.failure shouldBe "movement-blocked"
        }
    }

    test("one shared admission budget gives every queued route progress and cancellation releases search") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val owner = CivsResidentNavigation()
            val routes = List(48) {
                owner.request(terrain.world, Location(terrain.world, .5, 64.0, .5),
                    Location(terrain.world, 5.5, 64.0, .5), terrain::allowed, { _, _ -> true }, { 1.0 })
            }
            repeat(60) { owner.pulse() }
            routes.all { it.nodeCount > 0 } shouldBe true
            val searching = routes.filter { it.searching }
            owner.clear()
            searching.all { !it.searching && !it.navigating } shouldBe true
            routes.forEach(CitizenPacketRoute::cancel)
        }
    }

    test("one-block ascent and descent keep every intermediate body outside solid support") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
            val route = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 2.5, 64.0, .5),
                { point -> point.x in 0..2 && point.z == 0 && point.y in 64..65 }, { _, _ -> true }, { 1.0 })
            repeat(2_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            var climbed = false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketBodyClear(terrain.world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    if (next.y >= 65.0) climbed = true
                    at = next
                }
            }
            climbed shouldBe true
            route.finished shouldBe true
            route.failure shouldBe null
            at.distanceSquared(Location(terrain.world, 2.5, 64.0, .5)) shouldBe 0.0
        }
    }

    for (material in listOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE)) {
        test("work and social ingress routes walk around a lit $material without touching its footprint") {
            MockBukkitTestRuntime.open().use {
                val terrain = ResidentRouteTerrain()
                val fire = CivPosition(2, 64, 0)
                terrain.blocks[fire] = material
                val seat = citizenCampSeats(fire, UUID(0, 1)).first { it.x == 4 && it.y == 64 && it.z == 0 }
                for (target in listOf(CivPosition(5, 64, 0), seat)) {
                    // Long idle ingress shares the ordinary route gate with work travel.
                    var at = Location(terrain.world, -9.5, 64.0, .5)
                    val route = CitizenPacketRoute(terrain.world, at,
                        Location(terrain.world, target.x + .5, target.y.toDouble(), target.z + .5),
                        { p -> p.x in -10..8 && p.z in -5..5 && p.y in 64..65 }, { _, _ -> true }, { 1.0 })
                    repeat(20_000) { if (route.searching) route.expand() }
                    route.failure shouldBe null
                    route.searching shouldBe false
                    repeat(200) {
                        route.move(at, 1.0) { true }?.let { next ->
                            (next.x + .299 > fire.x && next.x - .299 < fire.x + 1 &&
                                next.z + .299 > fire.z && next.z - .299 < fire.z + 1) shouldBe false
                            (at.distance(next) <= .215001) shouldBe true
                            at = next
                        }
                    }
                    route.finished shouldBe true
                    at.distanceSquared(Location(terrain.world, target.x + .5, target.y.toDouble(), target.z + .5)) shouldBe 0.0
                }
            }
        }

        test("lighting a planned $material foothold stops movement before the resident enters it") {
            MockBukkitTestRuntime.open().use {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(2, 64, 0)] = material
                val fire = terrain.world.getBlockAt(2, 64, 0).blockData as Lightable
                fire.isLit = false
                val route = terrain.route()
                repeat(2_000) { if (route.searching) route.expand() }
                route.failure shouldBe null
                fire.isLit = true // Shapes remain identical; execution must recheck the heat.
                var at = Location(terrain.world, .5, 64.0, .5)
                repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
                route.failure shouldBe "movement-blocked"
                (at.x + .299 <= 2.0) shouldBe true
                citizenPacketBodyClear(terrain.world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
            }
        }

        test("a resident already above a lit $material can walk off it but cannot walk back") {
            MockBukkitTestRuntime.open().use {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(2, 64, 0)] = material
                val exposed = CitizenPoint(2.5, 64.4375, .5)
                citizenPacketSupported(terrain.world, exposed) shouldBe true
                citizenPacketBodyClear(terrain.world, exposed, false) shouldBe true
                citizenPacketCampfireClear(terrain.world, exposed) shouldBe false
                val route = CitizenPacketRoute(terrain.world, Location(terrain.world, exposed.x, exposed.y, exposed.z),
                    Location(terrain.world, 5.5, 64.0, .5), terrain::allowed, { _, _ -> true }, { 1.0 })
                repeat(2_000) { if (route.searching) route.expand() }
                route.failure shouldBe null
                var at = Location(terrain.world, exposed.x, exposed.y, exposed.z)
                repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
                route.finished shouldBe true
                at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0
                citizenPacketStepClear(terrain.world, CitizenPoint(3.5, exposed.y, .5), exposed, false) shouldBe false
            }
        }
    }

    test("campfire heat covers body-side and support rows and prevents a scaled nudge into new heat") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(2, 65, 0)] = Material.CAMPFIRE
            citizenPacketCampfireClear(terrain.world, CitizenPoint(1.75, 64.0, .5)) shouldBe false
            citizenPacketCampfireClear(terrain.world, CitizenPoint(1.7, 64.0, .5)) shouldBe true
            terrain.blocks.remove(CivPosition(2, 65, 0))
            terrain.blocks[CivPosition(2, 63, 0)] = Material.SOUL_CAMPFIRE
            val from = Location(terrain.world, 1.69, 64.0, .5)
            val to = from.clone().add(.05, 0.0, 0.0)
            citizenPacketBodyClear(terrain.world, CitizenPoint(to.x, to.y, to.z), false) shouldBe true
            val npc = mockk<PacketPlayerNpc> {
                every { isValid } returns true
                every { world } returns terrain.world
                every { scale } returns 1.0
                every { height } returns 1.8
            }
            citizenResidentNudgeClear(npc, from, to) shouldBe false
            every { npc.scale } returns .5
            every { npc.height } returns .9
            citizenResidentNudgeClear(npc, from, to) shouldBe true
        }
    }

    test("idle route crosses authored log floor beams without allowing an outside tree route") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 0..5) terrain.blocks[CivPosition(x, 63, 0)] = Material.OAK_PLANKS
            // medievaloak/townhall1 has log beams across its plank floor, including under
            // an interior door. A full-width beam must not partition a resident's room.
            terrain.blocks[CivPosition(2, 63, 0)] = Material.OAK_LOG
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0,
                "civs/minecolonies/medievaloak/fundamentals/townhall1",
                CivBox(CivPosition(0, 63, -1), CivPosition(5, 66, 1)), CivPosition(0, 64, 0), BuildingPhase.ACTIVE)
            fun route(buildings: List<CivBuilding>) = CitizenPacketRoute(terrain.world,
                Location(terrain.world, .5, 64.0, .5), Location(terrain.world, 5.5, 64.0, .5),
                { it.x in 0..5 && it.y == 64 && it.z == 0 && citizenIdleTerrain(terrain.world, it, buildings) },
                { _, _ -> true }, { 1.0 })
            val outside = route(emptyList())
            repeat(2_000) { if (outside.searching) outside.expand() }
            outside.failure shouldBe "no-path"

            val indoors = route(listOf(hall))
            repeat(2_000) { if (indoors.searching) indoors.expand() }
            indoors.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            repeat(80) { indoors.move(at, 1.0) { true }?.let { at = it } }
            indoors.finished shouldBe true
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("temporary player collision preserves the waypoint and resumes when the passage clears") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val route = terrain.route()
            repeat(2_000) { if (route.searching) route.expand() }
            val waypoint = requireNotNull(route.destination)
            var at = Location(terrain.world, .5, 64.0, .5)
            repeat(10) {
                route.move(at, 1.0, { _, _ -> false }) { true } shouldBe null
                route.destination shouldBe waypoint
                route.waitingForCollision shouldBe true
                route.failure shouldBe null
                route.navigating shouldBe true
            }
            repeat(80) { route.move(at, 1.0, { _, _ -> true }) { true }?.let { at = it } }
            route.waitingForCollision shouldBe false
            route.finished shouldBe true
            route.failure shouldBe null
            at.distanceSquared(Location(terrain.world, 5.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("five nearby destinations share a walked trunk while a distant parallel trip keeps its short route") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val city = pathsCity().copy(worldId = terrain.world.uid)
            val paths = CivsPaths()
            fun walk(fromZ: Int, toZ: Int): Set<CivPosition> {
                val trip = paths.begin(city)
                var at = Location(terrain.world, .5, 64.0, fromZ + .5)
                val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 16.5, 64.0, toZ + .5),
                    { p -> p.x in 0..16 && p.z in -4..6 && p.y == 64 }, { _, _ -> true },
                    { block -> trip.cost(block) { 1.0 } })
                repeat(20_000) { if (route.searching) route.expand() }
                route.failure shouldBe null
                route.searching shouldBe false
                val walked = linkedSetOf<CivPosition>()
                repeat(400) {
                    trip.observe(CitizenPoint(at.x, at.y, at.z))
                    route.move(at, 1.0) { true }?.let { next ->
                        walked += CivPosition(next.blockX, citizenSupportY(next.y), next.blockZ)
                        trip.observe(CitizenPoint(next.x, next.y, next.z))
                        at = next
                    }
                }
                route.finished shouldBe true
                paths.complete(city, trip)
                repeat(20) { paths.pulse() }
                return walked
            }
            walk(0, 0)
            val nearby = listOf(-2, -1, 0, 1, 2).map { walk(0, it) }
            nearby.all { it.count { p -> p.z == 0 && p.x in 2..12 } >= 9 } shouldBe true
            paths.remembered(city.id).none { it.x in 2..10 && it.z in setOf(-2, -1, 1, 2) } shouldBe true
            walk(6, 6).none { it.z == 0 } shouldBe true
        }
    }

    test("corridor preference cannot cross a protected footprint or climb an outdoor log") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val city = pathsCity().copy(worldId = terrain.world.uid)
            val paths = CivsPaths()
            val learned = paths.begin(city)
            for (x in 0..5) learned.observe(CitizenPoint(x + .5, 64.0, .5))
            paths.complete(city, learned)
            paths.pulse()
            val preferred = paths.begin(city)
            val house = city.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
                bounds = CivBox(CivPosition(2, 63, -1), CivPosition(3, 66, 1)))
            val protectedCity = city.copy(buildings = city.buildings + house)
            val detour = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 5.5, 64.0, .5),
                { p -> terrain.allowed(p) && citizenRouteCellAllowed(protectedCity, emptySet(), p.x, p.z) },
                { _, _ -> true }, { block -> preferred.cost(block) { 1.0 } })
            repeat(20_000) { if (detour.searching) detour.expand() }
            detour.failure shouldBe null
            var at = Location(terrain.world, .5, 64.0, .5)
            repeat(160) { detour.move(at, 1.0) { true }?.let { next ->
                citizenRouteCellAllowed(protectedCity, emptySet(), next.blockX, next.blockZ) shouldBe true
                at = next
            } }
            detour.finished shouldBe true

            terrain.blocks[CivPosition(2, 63, 0)] = Material.OAK_LOG
            val tree = CitizenPacketRoute(terrain.world, Location(terrain.world, .5, 64.0, .5),
                Location(terrain.world, 5.5, 64.0, .5), { p -> p.x in 0..5 && p.y == 64 && p.z == 0 },
                { _, _ -> true }, { block -> preferred.cost(block) { 1.0 } })
            repeat(2_000) { if (tree.searching) tree.expand() }
            tree.failure shouldBe "no-path"
        }
    }

    test("existing roads beat a modest learned grass shortcut but not an excessive detour") {
        MockBukkitTestRuntime.open().use {
            for (material in listOf(Material.DIRT_PATH, Material.COBBLESTONE)) {
                fun routeOnRoad(detour: Int): Set<CivPosition> {
                    val terrain = ResidentRouteTerrain()
                    val city = pathsCity().copy(worldId = terrain.world.uid)
                    val paths = CivsPaths()
                    val shortcut = paths.begin(city)
                    for (x in 0..16) shortcut.observe(CitizenPoint(x + .5, 64.0, .5))
                    paths.complete(city, shortcut)
                    repeat(2) { paths.pulse() }
                    for (x in 0..16) terrain.blocks[CivPosition(x, 63, detour)] = material
                    for (z in 0..detour) for (x in listOf(0, 16)) terrain.blocks[CivPosition(x, 63, z)] = material
                    val trip = paths.begin(city)
                    var at = Location(terrain.world, .5, if (material == Material.DIRT_PATH) 63.9375 else 64.0, .5)
                    val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 16.5, at.y, .5),
                        { p -> p.x in 0..16 && p.z in 0..detour && p.y in 63..64 }, { _, _ -> true },
                        { ground -> trip.cost(ground) { if (it.type == Material.COBBLESTONE) 1.3 else 1.0 } })
                    repeat(20_000) { if (route.searching) route.expand() }
                    route.failure shouldBe null
                    route.searching shouldBe false
                    val walked = linkedSetOf<CivPosition>()
                    repeat(600) {
                        route.move(at, 1.0) { true }?.let { next ->
                            walked += CivPosition(next.blockX, citizenSupportY(next.y), next.blockZ)
                            at = next
                        }
                    }
                    route.finished shouldBe true
                    return walked
                }
                val modest = routeOnRoad(3)
                withClue("$material should reuse the existing road despite the learned direct shortcut") {
                    modest.count { it.x in 2..14 && it.z == 3 } shouldBe 13
                }
                val excessive = routeOnRoad(10)
                withClue("$material should not turn a short walk into a large road detour") {
                    excessive.none { it.z == 10 } shouldBe true
                    excessive.count { it.x in 2..14 && it.z == 0 } shouldBe 13
                }
            }
        }
    }

    test("a path at fifteen sixteenths height reaches its endpoint and emits every actual cell crossing") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 0..5) terrain.blocks[CivPosition(x, 63, 0)] = Material.DIRT_PATH
            val trip = CivsPaths().begin(pathsCity().copy(worldId = terrain.world.uid))
            val first = terrain.world.getBlockAt(0, 63, 0)
            trip.cost(first) { 5.0 } shouldBe 1.0 // Explicit path wins over a provider's recognized surface.
            val route = CitizenPacketRoute(terrain.world, Location(terrain.world, .95, 63.9375, .5),
                Location(terrain.world, 5.5, 64.0, .5), { p -> p.x in 0..5 && p.z == 0 && p.y in 63..64 },
                { _, _ -> true }, { block -> trip.cost(block) { 1.0 } })
            repeat(2_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            route.searching shouldBe false
            var at = Location(terrain.world, .95, 63.9375, .5)
            val steps = CitizenFootsteps()
            val departed = mutableListOf<CivPosition>()
            repeat(80) {
                val before = at
                route.move(at, 1.0) { true }?.let { next ->
                    steps.prime(before, citizenPacketSupported(terrain.world, CitizenPoint(before.x, before.y, before.z)))
                    steps.observe(next, citizenPacketSupported(terrain.world, CitizenPoint(next.x, next.y, next.z)))?.let { (from, to) ->
                        if (from.blockX != to.blockX || from.blockZ != to.blockZ)
                            departed += CivPosition(from.blockX, citizenSupportY(from.y), from.blockZ)
                    }
                    at = next
                }
            }
            route.finished shouldBe true
            at.y shouldBe 63.9375
            at.x shouldBe 5.5
            departed shouldBe (0..4).map { CivPosition(it, 63, 0) }
        }
    }

    test("heading takes the short turn across wraparound and terrain cannot load a missing chunk") {
        citizenTurn(179f, -179f, 24f) shouldBe 181f
        citizenTurn(0f, 120f, 24f) shouldBe 24f
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.loaded = false
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe false
            terrain.blockReads shouldBe 0
        }
    }
})

/** Explicit geometric unit fixture. It does not model a live server or claim native client movement. */
private class ResidentRouteTerrain {
    val blocks = mutableMapOf<CivPosition, Material>()
    var loaded = true
    var blockReads = 0
    val world = mockk<World>()
    private val chunk = mockk<Chunk>()
    private val native = mutableMapOf<CivPosition, Block>()
    private val blockDataByMaterial = mutableMapOf<Material, org.bukkit.block.data.BlockData>()
    init {
        every { world.uid } returns UUID.randomUUID()
        every { world.minHeight } returns 0
        every { world.maxHeight } returns 256
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
        every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            blockReads++
            val point = CivPosition(firstArg(), secondArg(), thirdArg())
            native.getOrPut(point) {
                val shape = mockk<VoxelShape> {
                    every { boundingBoxes } answers {
                        if (material(point).isSolid) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, when (material(point)) {
                            Material.DIRT_PATH -> .9375
                            Material.CAMPFIRE, Material.SOUL_CAMPFIRE -> .4375
                            else -> 1.0
                        }, 1.0)) else emptyList()
                    }
                }
                val block = mockk<Block> {
                    every { x } returns point.x
                    every { y } returns point.y
                    every { z } returns point.z
                    every { type } answers { material(point) }
                    every { isLiquid } returns false
                    every { blockData } answers { blockDataByMaterial.getOrPut(material(point)) { material(point).createBlockData() } }
                    every { collisionShape } returns shape
                }
                // Geometry has explicit assertions/counters. Retaining every probe across
                // 48 routes only grows MockK's call history and is not part of this oracle.
                excludeRecords {
                    block.type; block.isLiquid; block.blockData; block.collisionShape
                    block.x; block.y; block.z
                    shape.boundingBoxes
                }
                block
            }
        }
        excludeRecords {
            world.uid; world.minHeight; world.maxHeight
            world.isChunkLoaded(any<Int>(), any<Int>())
            world.getChunkAt(any<Int>(), any<Int>(), false)
            world.getBlockAt(any<Int>(), any<Int>(), any<Int>())
            chunk.loadLevel
        }
    }
    private fun material(point: CivPosition) = blocks[point] ?: if (point.y <= 63) Material.STONE else Material.AIR
    fun allowed(point: CivPosition) = point.x in -3..8 && point.z in -5..5 && point.y in 64..65
    fun route() = CitizenPacketRoute(world, Location(world, .5, 64.0, .5), Location(world, 5.5, 64.0, .5),
        ::allowed, { _, _ -> true }, { 1.0 })
}
