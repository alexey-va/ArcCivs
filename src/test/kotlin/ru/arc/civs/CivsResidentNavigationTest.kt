package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.excludeRecords
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Bukkit
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
    test("a fractional ladder start descends onto its current rung and turns out through a low landing") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("fractional-ladder").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            for (x in -1..3) for (z in -1..1) for (y in 64..68) world.getBlockAt(x, y, z).type = Material.STONE
            for (y in 64..67) world.getBlockAt(0, y, 0).type = Material.AIR
            for (y in 64..65) world.getBlockAt(0, y, 0).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
            for (x in 1..2) for (y in 64..65) world.getBlockAt(x, y, 0).type = Material.AIR
            var at = Location(world, .57, 65.2, .55)
            citizenPacketBodyClear(world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
            val route = CitizenPacketRoute(world, at, Location(world, 2.5, 64.0, .5),
                { p -> p.x in 0..2 && p.z == 0 && p.y in 64..66 }, { _, _ -> true }, { 1.0 })
            repeat(3_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            route.searching shouldBe false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    for (sample in 0..10) {
                        val p = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                        citizenPacketBodyClear(world, CitizenPoint(p.x, p.y, p.z), false) shouldBe true
                    }
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(Location(world, 2.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("an interrupted step can settle in its own column before walking under a low ceiling") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 1..3) terrain.blocks[CivPosition(x, 66, 0)] = Material.STONE
            var at = Location(terrain.world, .5, 65.0, .5)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 3.5, 64.0, .5),
                { p -> p.x in 0..3 && p.z == 0 && p.y in 64..65 }, { _, _ -> true }, { 1.0 })
            repeat(3_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
            route.finished shouldBe true
            at.distanceSquared(Location(terrain.world, 3.5, 64.0, .5)) shouldBe 0.0
        }
    }

    test("exact vertical endpoints preserve the incoming yaw and bounded pitch on ascent descent and settling") {
        MockBukkitTestRuntime.open().use {
            for ((startY, targetY) in listOf(64.0 to 65.0, 65.0 to 64.0, 64.1875 to 64.0)) {
                val terrain = ResidentRouteTerrain()
                if (startY % 1.0 == 0.0) for (y in 64..66)
                    terrain.blocks[CivPosition(0, y, 0)] = Material.LADDER
                for (yaw in listOf(47f, 179f, -179f, 539f, -539f)) {
                    var at = Location(terrain.world, .5, startY, .5, yaw, 24f)
                    val target = Location(terrain.world, .5, targetY, .5)
                    val route = CitizenPacketRoute(terrain.world, at, target,
                        { it.x == 0 && it.z == 0 && it.y in 64..66 }, { _, _ -> true }, { 1.0 })
                    repeat(256) { if (route.searching) route.expand() }
                    route.searching shouldBe false
                    route.failure shouldBe null
                    var moves = 0
                    repeat(16) { tick ->
                        route.move(at, 1.0) { true }?.let { next ->
                            withClue("fromY=$startY targetY=$targetY yaw=$yaw tick=$tick from=$at next=$next") {
                                next.x shouldBe at.x
                                next.z shouldBe at.z
                                next.yaw shouldBe yaw
                                next.pitch shouldBe maxOf(0f, at.pitch - CITIZEN_TURN_PER_TICK)
                                (at.distance(next) in .000001.. .215001) shouldBe true
                                citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                                    CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                            }
                            at = next
                            moves++
                        }
                    }
                    (moves > 0) shouldBe true
                    route.failure shouldBe null
                    route.finished shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                    at.yaw shouldBe yaw
                    if (startY % 1.0 != 0.0) at.pitch shouldBe 16f
                }
            }
        }
    }

    test("sleep clearance rotates the full native body and rejects walls and fire beyond standing feet") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val feet = Location(terrain.world, .5, 64.0, .5)
            val open = requireNotNull(citizenSleepPose(terrain.world, feet, 1.0))
            open.position.yaw shouldBe 0f
            val neighbour = requireNotNull(citizenSleepPose(terrain.world, feet.clone().add(0.0, 0.0, 1.0), 1.0) {
                !it.overlaps(open.bounds)
            })
            neighbour.position.yaw shouldBe 90f
            neighbour.bounds.overlaps(open.bounds) shouldBe false
            citizenSleepPose(terrain.world, feet, 1.0) { !it.overlaps(open.bounds) } shouldBe null
            (open.bounds.minY > feet.y) shouldBe true
            // The native armour head inflates its 8px cube by 1px on every face.
            val helmetRadius = 5.0 / 16.0 * .9375
            (open.bounds.minY < open.position.y - helmetRadius) shouldBe true
            (open.bounds.maxY > open.position.y + helmetRadius) shouldBe true
            val bobbingArmWidth = (5 + 4 * kotlin.math.cos(.1) + 11 * kotlin.math.sin(.1)) / 16 * .9375
            (open.bounds.minZ < open.position.z - bobbingArmWidth) shouldBe true
            (open.bounds.maxZ > open.position.z + bobbingArmWidth) shouldBe true
            (open.bounds.minX < -1.4) shouldBe true
            terrain.blocks[CivPosition(-1, 64, 0)] = Material.STONE
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe true
            val turned = requireNotNull(citizenSleepPose(terrain.world, feet, 1.0))
            (turned.position.yaw != 0f) shouldBe true
            citizenSleepClear(terrain.world, open.bounds) shouldBe false
            terrain.blocks[CivPosition(-1, 64, 0)] = Material.CAMPFIRE
            citizenSleepClear(terrain.world, open.bounds) shouldBe false
            for ((x, z) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1))
                terrain.blocks[CivPosition(x, 64, z)] = Material.STONE
            citizenSleepPose(terrain.world, feet, 1.0) shouldBe null
            citizenPacketBodyClear(terrain.world, CitizenPoint(.5, 64.0, .5), false) shouldBe true
        }
    }

    test("walking keeps the full per-tick distance across straight and diagonal cell centres") {
        MockBukkitTestRuntime.open().use {
            for (diagonal in listOf(false, true)) for (speed in listOf(.55, 1.0, 1.3)) {
                val terrain = ResidentRouteTerrain()
                var at = Location(terrain.world, .5, 64.0, .5, if (diagonal) -45f else -90f, 0f)
                val route = CitizenPacketRoute(terrain.world, at,
                    Location(terrain.world, 20.5, 64.0, if (diagonal) 20.5 else .5),
                    { p -> p.x in 0..20 && p.z in 0..20 && p.y == 64 }, { _, _ -> true }, { 1.0 })
                repeat(20_000) { if (route.searching) route.expand() }
                route.searching shouldBe false
                route.failure shouldBe null
                repeat(60) { tick ->
                    val next = requireNotNull(route.move(at, speed) { true })
                    withClue("diagonal=$diagonal speed=$speed tick=$tick from=$at to=$next") {
                        (kotlin.math.abs(at.distance(next) - .215 * speed) < .000001) shouldBe true
                    }
                    at = next
                }
            }
        }
    }

    test("speed changes collision waits and cancellation never bank movement for a later tick") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 20.5, 64.0, .5),
                { p -> p.x in 0..20 && p.z == 0 && p.y == 64 }, { _, _ -> true }, { 1.0 })
            repeat(20_000) { if (route.searching) route.expand() }
            repeat(4) { at = requireNotNull(route.move(at, 1.0) { true }) }
            // The next step crosses a waypoint. A player blocking that tick must leave its
            // full budget unspent rather than allow a jump when the player moves away.
            val blockedAt = at.clone()
            repeat(7) {
                route.move(at, 1.0, { _, _ -> false }) { true } shouldBe null
                route.waitingForCollision shouldBe true
                at shouldBe blockedAt
            }
            for (speed in listOf(.55, 1.3, 1.0, .55, 1.3, 1.0)) {
                val next = requireNotNull(route.move(at, speed) { true })
                (kotlin.math.abs(at.distance(next) - .215 * speed) < .000001) shouldBe true
                at = next
            }
            route.cancel()
            route.move(at, 1.3) { true } shouldBe null
            route.navigating shouldBe false
        }
    }

    test("the first diagonal on a fractional-height path is checked from the actual off-centre position") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 0..4) for (z in 0..2) terrain.blocks[CivPosition(x, 63, z)] = Material.DIRT_PATH
            for (y in 64..65) terrain.blocks[CivPosition(1, y, -1)] = Material.STONE
            var at = Location(terrain.world, .69, 63.9375, .31)
            citizenPacketBodyClear(terrain.world, CitizenPoint(at.x, at.y, at.z), false) shouldBe true
            val origin = CitizenPoint(at.x, at.y, at.z)
            val plannedStarts = mutableListOf<CitizenPoint>()
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 4.5, 64.0, .5),
                { p -> p.x in 0..4 && p.z in 0..2 && p.y in 63..64 },
                { from, _ -> plannedStarts += from; true }, { 1.0 })
            repeat(5_000) { if (route.searching) route.expand() }
            plannedStarts.first() shouldBe origin
            route.failure shouldBe null
            repeat(120) { route.move(at, 1.0) { true }?.let { at = it } }
            route.failure shouldBe null
            route.finished shouldBe true
            at.x shouldBe 4.5
            at.y shouldBe 63.9375
        }
    }

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
                    for (sample in 0..10) {
                        val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                        citizenPacketBodyClear(terrain.world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                    }
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
            second.move(Location(changed.world, .5, 64.0, .5, -90f, 0f), 1.0) { true } shouldBe null
            second.failure shouldBe "movement-blocked"
        }
    }

    test("a rounded doorway corner cannot skip the thin open door between its sampled endpoints") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in -72..-67) for (z in -49..-46) terrain.blocks[CivPosition(x, 70, z)] = Material.OAK_PLANKS
            for (y in 71..72) {
                terrain.blocks[CivPosition(-69, y, -48)] = Material.OAK_PLANKS
                val door = CivPosition(-69, y, -49)
                terrain.blocks[door] = Material.OAK_DOOR
                // Saved Spawn doorway: east-facing, right-hinged, open, with a 3/16 panel.
                terrain.boxes[door] = listOf(BoundingBox(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0))
            }
            val fourth = CitizenPoint(-67.67777777777778, 71.0, -48.45458333333333)
            val fifth = CitizenPoint(-67.77777777777779, 71.0, -48.48864583333333)
            citizenPacketBodyClear(terrain.world, fourth, false) shouldBe true
            citizenPacketBodyClear(terrain.world, fifth, false) shouldBe true
            citizenPacketStepClear(terrain.world, fourth, fifth, false) shouldBe false

            var at = Location(terrain.world, -67.5, 71.0, -46.5, 180f, 0f)
            val target = Location(terrain.world, -71.5, 71.0, -48.5)
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> p.y == 71 && (p.x == -68 && p.z in -49..-47 || p.z == -49 && p.x in -72..-68) },
                { _, _ -> true }, { 1.0 })
            repeat(5_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            repeat(160) { tick ->
                route.move(at, .55) { true }?.let { next ->
                    (at.distance(next) <= .118251) shouldBe true
                    at = next
                }
                withClue("tick=$tick at=$at") { route.failure shouldBe null }
            }
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
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

    for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) for (returning in listOf(false, true))
    test("a lowered path connects the farm stair and deck at direction $dx,$dz returning=$returning") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val stair = CivPosition(dx, 64, dz)
            terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
            terrain.blocks[stair] = Material.OAK_STAIRS
            // The observed medievaloak farm has a bottom straight stair above a
            // 15/16 dirt path. These are the vanilla shape boxes, rotated with its rise.
            terrain.boxes[stair] = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0), when {
                dx > 0 -> BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0)
                dx < 0 -> BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                dz > 0 -> BoundingBox(0.0, .5, .5, 1.0, 1.0, 1.0)
                else -> BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5)
            })
            terrain.blocks[CivPosition(dx * 2, 64, dz * 2)] = Material.OAK_PLANKS
            val path = Location(terrain.world, .5, 63.9375, .5)
            val deck = Location(terrain.world, dx * 2 + .5, 65.0, dz * 2 + .5)
            var at = (if (returning) deck else path).clone().also {
                val sign = if (returning) -1 else 1
                it.yaw = Math.toDegrees(kotlin.math.atan2(-dx.toDouble() * sign, dz.toDouble() * sign)).toFloat()
            }
            val target = if (returning) path else deck
            val route = CitizenPacketRoute(terrain.world, at, target,
                { p -> (0..2).any { p.x == dx * it && p.z == dz * it } && p.y in 63..65 },
                { _, _ -> true }, { 1.0 })
            repeat(2_000) { if (route.searching) route.expand() }
            route.searching shouldBe false
            route.failure shouldBe null
            var moved = 0
            repeat(100) {
                route.move(at, 1.0) { true }?.let { next ->
                    (at.distance(next) <= .215001) shouldBe true
                    for (sample in 0..10) {
                        val point = at.clone().add(next.toVector().subtract(at.toVector()).multiply(sample / 10.0))
                        citizenPacketBodyClear(terrain.world, CitizenPoint(point.x, point.y, point.z), false) shouldBe true
                    }
                    at = next
                    moved++
                }
            }
            (moved > 0) shouldBe true
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    test("a lowered path transition still respects a swept head obstacle and a protected destination") {
        MockBukkitTestRuntime.open().use {
            for (obstacle in listOf("headroom", "protected")) {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
                terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
                if (obstacle == "headroom") terrain.blocks[CivPosition(0, 66, 0)] = Material.STONE
                val from = CitizenPoint(.5, 63.9375, .5)
                val to = CitizenPoint(1.5, 65.0, .5)
                citizenPacketBodyClear(terrain.world, from, false) shouldBe true
                citizenPacketBodyClear(terrain.world, to, false) shouldBe true
                val route = CitizenPacketRoute(terrain.world,
                    Location(terrain.world, from.x, from.y, from.z), Location(terrain.world, to.x, to.y, to.z),
                    { p -> p.z == 0 && p.x in 0..1 && p.y in 63..65 && (obstacle != "protected" || p.x != 1) },
                    { _, _ -> true }, { 1.0 })
                repeat(2_000) { if (route.searching) route.expand() }
                withClue(obstacle) { route.failure shouldBe "no-path" }
            }
        }
    }

    test("rounded observed path feet settle onto native support before crossing the taller edge in either direction") {
        MockBukkitTestRuntime.open().use {
            for (returning in listOf(false, true)) {
                val terrain = ResidentRouteTerrain()
                terrain.blocks[CivPosition(0, 63, 0)] = Material.DIRT_PATH
                terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
                val low = Location(terrain.world, .5, 63.94, .5)
                val high = Location(terrain.world, 1.5, 65.0, .5)
                var at = (if (returning) high else low).clone()
                val target = if (returning) low else high
                val route = CitizenPacketRoute(terrain.world, at, target,
                    { p -> p.x in 0..1 && p.z == 0 && p.y in 63..65 }, { _, _ -> true }, { 1.0 })
                repeat(2_000) { if (route.searching) route.expand() }
                route.failure shouldBe null
                repeat(100) { route.move(at, 1.0) { true }?.let { at = it } }
                route.failure shouldBe null
                route.finished shouldBe true
                at.x shouldBe target.x
                at.z shouldBe target.z
                at.y shouldBe if (returning) 63.9375 else 65.0
                citizenPacketSupported(terrain.world, CitizenPoint(at.x, at.y, at.z)) shouldBe true
            }
        }
    }

    for (height in listOf(1.5, 2.0)) for (returning in listOf(false, true))
    test("a $height block ledge remains unreachable returning=$returning") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            terrain.blocks[CivPosition(1, 64, 0)] = Material.STONE
            terrain.blocks[CivPosition(1, 65, 0)] = if (height == 1.5) Material.OAK_SLAB else Material.STONE
            if (height == 1.5) terrain.boxes[CivPosition(1, 65, 0)] =
                listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
            val low = Location(terrain.world, .5, 64.0, .5)
            val high = Location(terrain.world, 1.5, 64.0 + height, .5)
            val route = CitizenPacketRoute(terrain.world, if (returning) high else low, if (returning) low else high,
                { p -> p.x in 0..1 && p.z == 0 && p.y in 64..66 }, { _, _ -> true }, { 1.0 })
            repeat(2_000) { if (route.searching) route.expand() }
            route.failure shouldBe "no-path"
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
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
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

    test("route movement takes the short turn after accumulated rotations in either direction") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (sign in listOf(1, -1)) {
                var at = Location(terrain.world, .5, 64.0, 3.5, sign * 539f, 0f)
                val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, .5, 64.0, .5),
                    { p -> p.x == 0 && p.z in 0..3 && p.y == 64 }, { _, _ -> true }, { 1.0 })
                for (step in 0 until 256) {
                    if (!route.searching) break
                    route.expand()
                }
                route.searching shouldBe false
                route.failure shouldBe null
                val waypoint = requireNotNull(route.destination)
                (waypoint.z < at.z) shouldBe true
                // A small observed nudge makes the next actual leg -179 / +179 degrees.
                // Anticipation can aim farther along that leg, but must still cross
                // the accumulated +/-540 boundary in the short, matching direction.
                at.x -= sign * kotlin.math.tan(Math.toRadians(1.0)) * (at.z - waypoint.z)
                val first = requireNotNull(route.move(at, 1.0) { true })
                withClue("sign=$sign from=${at.yaw} next=${first.yaw}") {
                    ((first.yaw - at.yaw) * sign in 1f..2.001f) shouldBe true
                }
                (kotlin.math.abs(at.distance(first) - .215) < .000001) shouldBe true
                var turned = kotlin.math.abs(first.yaw - at.yaw)
                at = first
                for (tick in 0 until 32) {
                    if (route.finished) break
                    val next = requireNotNull(route.move(at, 1.0) { true })
                    (at.distance(next) <= .215001) shouldBe true
                    turned += kotlin.math.abs(next.yaw - at.yaw)
                    at = next
                }
                route.failure shouldBe null
                route.finished shouldBe true
                at.x shouldBe .5
                at.z shouldBe .5
                (turned <= 3.001f) shouldBe true
            }
        }
    }

    test("a flat path corner is rounded within its corridor without stopping accelerating or crossing its walls") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            fun corridor(x: Int, z: Int) = x in 0..5 && z == 0 || x == 5 && z in 0..5
            for (x in -1..6) for (z in -1..6) {
                if (corridor(x, z)) terrain.blocks[CivPosition(x, 63, z)] = Material.DIRT_PATH
                else for (y in 64..65) terrain.blocks[CivPosition(x, y, z)] = Material.STONE
            }
            var at = Location(terrain.world, .5, 63.9375, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 5.5, 63.9375, 5.5),
                { corridor(it.x, it.z) && it.y in 63..64 }, { _, _ -> true }, { 1.0 })
            repeat(20_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            var rounded = false
            var turns = 0f
            repeat(160) { tick ->
                val progress = route.forwardProgress
                route.move(at, .8 * 1.25) { true }?.let { next ->
                    val delta = kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw))
                    (delta <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    turns += delta
                    (at.distance(next) <= .215001) shouldBe true
                    (route.forwardProgress - progress <= at.distance(next) + .000001) shouldBe true
                    citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                        CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    citizenPacketSupported(terrain.world, CitizenPoint(next.x, next.y, next.z)) shouldBe true
                    terrain.world.getBlockAt(next.blockX, citizenSupportY(next.y), next.blockZ).type shouldBe Material.DIRT_PATH
                    val departure = minOf(kotlin.math.abs(next.z - .5), kotlin.math.abs(next.x - 5.5))
                    (departure <= .151) shouldBe true
                    if (departure > .015) rounded = true
                    if (at.x > 4.8 && at.z < 1.2) withClue("tick=$tick from=$at next=$next turning=${route.turning}") {
                        (at.distance(next) > .15) shouldBe true
                    }
                    at = next
                }
            }
            rounded shouldBe true
            route.failure shouldBe null
            route.finished shouldBe true
            (turns < 110f) shouldBe true
            at.x shouldBe 5.5
            at.z shouldBe 5.5
        }
    }

    test("nudges and repeated walking over the same ordered segment cannot earn recovery progress") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 8.5, 64.0, .5),
                { it.x in -3..8 && it.z == 0 && it.y == 64 }, { _, _ -> true }, { 1.0 })
            repeat(3_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            repeat(3) { at = requireNotNull(route.move(at, 1.0) { true }) }
            (route.forwardProgress > .64) shouldBe true
            val highWater = at.clone()
            val earned = route.forwardProgress
            for (distance in listOf(.5, 1.0, 2.0)) {
                at = highWater.clone().subtract(distance, 0.0, 0.0)
                repeat(16) {
                    val remaining = highWater.x - at.x
                    if (remaining > .000001) at = requireNotNull(route.move(at, minOf(.215, remaining) / .215) { true })
                }
                (at.distance(highWater) < .000001) shouldBe true
                withClue("back-and-forth distance=$distance progress=${route.forwardProgress}") {
                    (kotlin.math.abs(route.forwardProgress - earned) < .000001) shouldBe true
                }
            }
            // A forward nudge raises the watermark without counting as accepted walking.
            val nudged = at.clone().add(.25, 0.0, 0.0)
            route.move(nudged, 1.0, { _, _ -> false }) { true } shouldBe null
            route.forwardProgress shouldBe earned
            repeat(2) {
                val remaining = nudged.x - at.x
                at = requireNotNull(route.move(at, minOf(.215, remaining) / .215) { true })
            }
            (kotlin.math.abs(route.forwardProgress - earned) < .000001) shouldBe true
            at = requireNotNull(route.move(at, .1 / .215) { true })
            (kotlin.math.abs(route.forwardProgress - earned - .1) < .000001) shouldBe true

            // Neither a newly planned path nor a different observed origin contributes credit.
            for (origin in listOf(at, at.clone().add(2.0, 0.0, 0.0))) {
                val replanned = route.replan(origin, emptyList())
                repeat(3_000) { if (replanned.searching) replanned.expand() }
                replanned.failure shouldBe null
                replanned.forwardProgress shouldBe 0.0
                replanned.move(origin, 1.0, { _, _ -> false }) { true }
                replanned.forwardProgress shouldBe 0.0
            }
        }
    }

    test("a validated detour earns forward path progress while initially walking away from the final goal") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            fun corridor(x: Int, z: Int) = x in 0..4 && z in listOf(0, 2) || x == 4 && z in 0..2
            for (x in -1..5) for (z in -1..3) if (!corridor(x, z))
                for (y in 64..65) terrain.blocks[CivPosition(x, y, z)] = Material.STONE
            var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
            val goal = Location(terrain.world, .5, 64.0, 2.5)
            val initialDistance = at.distance(goal)
            val route = CitizenPacketRoute(terrain.world, at, goal,
                { corridor(it.x, it.z) && it.y == 64 }, { _, _ -> true }, { 1.0 })
            repeat(20_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            repeat(3) { at = requireNotNull(route.move(at, 1.0) { true }) }
            (at.distance(goal) > initialDistance) shouldBe true
            (route.forwardProgress > .64) shouldBe true
            repeat(160) { route.move(at, 1.0) { true }?.let { next ->
                (at.distance(next) <= .215001) shouldBe true
                (kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw)) <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                    CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                at = next
            } }
            route.failure shouldBe null
            route.finished shouldBe true
            (at.distance(goal) < .000001) shouldBe true
            (route.forwardProgress > 9.0) shouldBe true
        }
    }

    test("an opposite-facing resident turns on its support before rising onto the next step") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            for (x in 1..3) terrain.blocks[CivPosition(x, 64, 0)] = Material.STONE
            var at = Location(terrain.world, .5, 64.0, .5, 90f, 0f)
            val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 3.5, 65.0, .5),
                { it.x in 0..3 && it.z == 0 && it.y in 64..65 }, { _, _ -> true }, { 1.0 })
            repeat(3_000) { if (route.searching) route.expand() }
            route.failure shouldBe null
            var stationaryTurns = 0
            var advanced = false
            repeat(80) {
                route.move(at, 1.0) { true }?.let { next ->
                    (kotlin.math.abs(citizenAngleChange(at.yaw, next.yaw)) <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    if (!advanced && next.distanceSquared(at) > .000001) {
                        (kotlin.math.abs(citizenAngleChange(at.yaw, -90f)) <= 65f) shouldBe true
                        advanced = true
                    } else if (!advanced) {
                        citizenPacketSupported(terrain.world, CitizenPoint(next.x, next.y, next.z)) shouldBe true
                        next.y shouldBe 64.0
                        stationaryTurns++
                    }
                    citizenPacketStepClear(terrain.world, CitizenPoint(at.x, at.y, at.z),
                        CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                    at = next
                }
            }
            stationaryTurns shouldBe 15
            advanced shouldBe true
            route.failure shouldBe null
            route.finished shouldBe true
            at.x shouldBe 3.5
            at.y shouldBe 65.0
        }
    }

    test("work facing route corners and a nudged replan share bounded packet-shaped body and head frames") {
        MockBukkitTestRuntime.open().use {
            for (sign in listOf(1, -1)) {
                val terrain = ResidentRouteTerrain()
                fun corridor(x: Int, z: Int) = x == 0 && z in 0..5 || z == 0 && x in 0..5
                for (x in -1..6) for (z in -1..6) {
                    if (corridor(x, z)) terrain.blocks[CivPosition(x, 63, z)] = Material.DIRT_PATH
                    else for (y in 64..65) terrain.blocks[CivPosition(x, y, z)] = Material.STONE
                }
                var position = Location(terrain.world, .5, 63.9375, 5.5, sign * 539f, 0f)
                var body = position.yaw
                var head = body
                var pitch = 0f
                val npc = mockk<PacketPlayerNpc> {
                    every { location } answers { position.clone().also { it.yaw = body; it.pitch = pitch } }
                    every { bodyYaw } answers { body }
                    every { headYaw } answers { head }
                    every { this@mockk.pitch } answers { pitch }
                    every { bodyYaw = any() } answers { body = firstArg() }
                    every { headYaw = any() } answers { head = firstArg() }
                    every { this@mockk.pitch = any() } answers { pitch = firstArg() }
                    every { teleport(any()) } answers { position = firstArg<Location>().clone(); body = position.yaw; pitch = position.pitch }
                }
                fun quantized(yaw: Float) = kotlin.math.floor(yaw * 256.0 / 360.0).toInt().toByte() * 360f / 256f
                var previousBody = body
                var previousHead = head
                var previousPitch = pitch
                var total = 0f
                fun frame() {
                    val change = kotlin.math.abs(citizenAngleChange(previousBody, body))
                    (change <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(quantized(previousBody), quantized(body))) <= 9.5f) shouldBe true
                    (kotlin.math.abs(citizenAngleChange(quantized(previousHead), quantized(head))) <= 9.5f) shouldBe true
                    (kotlin.math.abs(pitch - previousPitch) <= CITIZEN_TURN_PER_TICK + .001) shouldBe true
                    (kotlin.math.abs(quantized(pitch) - quantized(previousPitch)) <= 9.5f) shouldBe true
                    total += change
                    previousBody = body
                    previousHead = head
                    previousPitch = pitch
                }
                repeat(6) { citizenFace(npc, -sign * 179f, 48f); frame() }
                // An exact settling endpoint must not reset either packet-shaped
                // angle before the following horizontal route takes ownership.
                val grounded = npc.location
                npc.teleport(grounded.clone().add(0.0, .125, 0.0))
                val settling = CitizenPacketRoute(terrain.world, npc.location, grounded,
                    { corridor(it.x, it.z) && it.y in 63..64 }, { _, _ -> true }, { 1.0 })
                repeat(256) { if (settling.searching) settling.expand() }
                val settled = requireNotNull(settling.move(npc.location, .8 * 1.25) { true })
                npc.teleport(settled)
                npc.headYaw = settled.yaw
                settling.finished shouldBe true
                npc.bodyYaw shouldBe previousBody
                npc.headYaw shouldBe previousHead
                frame()
                var route = CitizenPacketRoute(terrain.world, npc.location, Location(terrain.world, 5.5, 63.9375, .5),
                    { corridor(it.x, it.z) && it.y in 63..64 }, { _, _ -> true }, { 1.0 })
                repeat(20_000) { if (route.searching) route.expand() }
                var replanned = false
                repeat(180) {
                    val before = npc.location
                    route.move(before, .8 * 1.25) { true }?.let { next ->
                        npc.teleport(next)
                        npc.headYaw = next.yaw
                        (before.distance(npc.location) <= .215001) shouldBe true
                    }
                    frame()
                    if (!replanned && npc.location.z < 3.4) {
                        val pushed = npc.location.add(.05, 0.0, 0.0)
                        npc.teleport(pushed)
                        route = route.replan(npc.location, emptyList()).also { route.cancel() }
                        repeat(20_000) { if (route.searching) route.expand() }
                        route.failure shouldBe null
                        replanned = true
                    }
                }
                replanned shouldBe true
                route.finished shouldBe true
                route.failure shouldBe null
                pitch shouldBe 0f
                repeat(24) { citizenFace(npc, 180f, 20f); frame() }
                (total < 210f) shouldBe true
                (kotlin.math.abs(citizenAngleChange(body, 180f)) < .001) shouldBe true
            }
        }
    }

    test("bounded collision replans detour around a person and preserve a narrow waiting route when no detour exists") {
        MockBukkitTestRuntime.open().use {
            val terrain = ResidentRouteTerrain()
            val person = BoundingBox(1.9, 64.0, .2, 2.5, 65.8, .8)
            for (narrow in listOf(false, true)) {
                var at = Location(terrain.world, .5, 64.0, .5, -90f, 0f)
                val route = CitizenPacketRoute(terrain.world, at, Location(terrain.world, 5.5, 64.0, .5),
                    { it.x in 0..5 && it.y == 64 && if (narrow) it.z == 0 else it.z in -2..2 },
                    { _, _ -> true }, { 1.0 })
                repeat(20_000) { if (route.searching) route.expand() }
                val detour = route.replan(at, listOf(person))
                repeat(20_000) { if (detour.searching) detour.expand() }
                if (narrow) {
                    detour.failure shouldBe "no-path"
                    route.navigating shouldBe true
                    repeat(80) { route.move(at, 1.0) { true }?.let { at = it } }
                    route.finished shouldBe true
                } else {
                    detour.failure shouldBe null
                    var avoided = false
                    repeat(100) { detour.move(at, 1.0) { true }?.let { next ->
                        citizenSlideBody(at.toVector(), next.toVector(), .299, 1.798, .299, listOf(person)) shouldBe null
                        if (kotlin.math.abs(next.z - .5) > .8) avoided = true
                        at = next
                    } }
                    detour.finished shouldBe true
                    avoided shouldBe true
                }
                at.x shouldBe 5.5
                at.z shouldBe .5
            }
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
    val boxes = mutableMapOf<CivPosition, List<BoundingBox>>()
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
                        boxes[point] ?: if (material(point).isSolid) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, when (material(point)) {
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
