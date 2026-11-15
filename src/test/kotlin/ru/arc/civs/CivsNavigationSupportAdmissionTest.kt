package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest
import org.bukkit.block.data.type.Stairs
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.floor

class CivsNavigationSupportAdmissionTest : FunSpec({
    test("sleep requires floor support even when movement can hold a ladder") {
        supportAdmissionWorld { world ->
            val ladder = Bukkit.createBlockData("minecraft:ladder[facing=north,waterlogged=false]")
            for (y in 63..65) {
                world.getBlockAt(0, y, 0).blockData = ladder.clone()
                world.getBlockAt(0, y, 1).type = Material.OAK_PLANKS
            }
            val feet = Location(world, .5, 64.0, .5)
            citizenPacketBodyClear(world, feet.point(), false) shouldBe true
            citizenPacketSupported(world, feet.point()) shouldBe true
            (citizenSleepPose(world, feet, 1.0) == null) shouldBe true

            world.getBlockAt(0, 63, 0).type = Material.OAK_PLANKS
            (citizenSleepPose(world, feet, 1.0) != null) shouldBe true
        }
    }

    test("a courier walks around a fence and carpet table instead of selecting an unsupported tall foothold") {
        supportAdmissionWorld { world ->
            val slab = "minecraft:acacia_slab[type=bottom,waterlogged=false]"
            world.getBlockAt(0, 63, 0).blockData = Bukkit.createBlockData(slab)
            world.getBlockAt(0, 63, -1).blockData = Bukkit.createBlockData(
                "minecraft:spruce_fence[east=false,north=false,south=false,waterlogged=false,west=false]")
            world.getBlockAt(0, 64, -1).type = Material.BROWN_CARPET
            world.getBlockAt(0, 63, -2).blockData = Bukkit.createBlockData(slab)
            var at = Location(world, .5, 63.5, .5, 180f, 0f)
            val target = Location(world, .5, 63.0, -3.5)
            val route = CitizenPacketRoute(world, at, target,
                { it.x in 0..1 && it.z in -4..0 && it.y in 63..65 }, { _, _ -> true }, { 1.0 })
            route.prepareSupportAdmission()
            route.failure shouldBe null
            repeat(300) {
                route.move(at, 1.0) { true }?.let { next ->
                    citizenPacketBodyClear(world, next.point(), false) shouldBe true
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
            world.getBlockAt(0, 63, -1).type shouldBe Material.SPRUCE_FENCE
            world.getBlockAt(0, 64, -1).type shouldBe Material.BROWN_CARPET
        }
    }

    test("a partial route walks across the inset chest seam from the observed idle return position") {
        supportAdmissionWorld { world ->
            for (cx in 2..4) for (cz in -1..0) world.loadChunk(cx, cz)
            for (x in 0..70) world.getBlockAt(x, 75, 0).type = Material.OAK_PLANKS
            world.getBlockAt(0, 76, 0).blockData = Bukkit.createBlockData("minecraft:chest[facing=south,type=left,waterlogged=false]")
            world.getBlockAt(1, 76, 0).blockData = Bukkit.createBlockData("minecraft:chest[facing=south,type=right,waterlogged=false]")
            var at = Location(world, .86499999999, 76.875, .5, -90f, 0f)
            val target = Location(world, 70.5, 76.0, .5)
            val route = CitizenPacketRoute(world, at, target, { it.x in 0..70 && it.z == 0 && it.y == 76 },
                { _, _ -> true }, { 1.0 }, settings = CivsNavigationSettings(maximumNodes = 64))
            repeat(10_000) { if (route.searching) route.expand() }
            route.continuationReason shouldBe "search-budget"
            route.destination?.point() shouldBe CitizenPoint(1.5, 76.875, .5)
            val seam = requireNotNull(route.move(at, .55) { true })
            (seam.x in .98324.. .98326) shouldBe true
            citizenPacketSupported(world, seam.point()) shouldBe false
            at = seam
            repeat(40) {
                route.move(at, .55) { true }?.let { next ->
                    (next.x - at.x in 0.0.. .118251) shouldBe true
                    citizenPacketBodyClear(world, next.point(), false) shouldBe true
                    at = next
                }
            }
            route.failure shouldBe null
            (route.forwardProgress > 3 && at.x > 4 && at.y == 76.0) shouldBe true
            route.finished shouldBe false

            // Losing the far chest leaves only one side of the feet supported.
            world.getBlockAt(1, 76, 0).type = Material.AIR
            citizenWalkingSupported(world, seam.point()) shouldBe false
        }
    }

    test("offset feet on a west stair retain native floor contact while turning northeast") {
        supportAdmissionWorld { world ->
            world.getBlockAt(0, 79, 0).blockData = Bukkit.createBlockData(
                "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]")
            world.getBlockAt(0, 79, -1).type = Material.GRASS_BLOCK
            world.getBlockAt(1, 79, -1).type = Material.GRASS_BLOCK
            var at = Location(world, .049769001525, 80.0, .5)
            val target = Location(world, 1.5, 80.0, -.5)
            val route = CitizenPacketRoute(world, at, target, { it.y == 80 && it.x in 0..1 && it.z in -1..0 },
                { _, _ -> true }, { 1.0 })
            route.prepareSupportAdmission()
            repeat(100) {
                route.move(at, .8) { true }?.let { next ->
                    withClue("offset stair $at -> $next") {
                        supportAdmissionFootContact(world, next) shouldBe true
                        citizenPacketBodyClear(world, next.point(), false) shouldBe true
                        citizenPacketStepClear(world, at.point(), next.point(), false) shouldBe true
                    }
                    (at.distance(next) <= .172001) shouldBe true
                    at = next
                }
            }
            route.failure shouldBe null
            route.finished shouldBe true
            at.distanceSquared(target) shouldBe 0.0
        }
    }

    test("ordinary slab stair and full block footholds remain traversable in both directions") {
        supportAdmissionWorld { world ->
            world.getBlockAt(0, 63, 0).blockData = Bukkit.createBlockData(
                "minecraft:acacia_slab[type=bottom,waterlogged=false]")
            world.getBlockAt(1, 63, 0).blockData = Bukkit.createBlockData(
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
            for (x in 2..3) world.getBlockAt(x, 63, 0).type = Material.OAK_PLANKS
            val low = Location(world, .5, 63.5, .5, -90f, 0f)
            val high = Location(world, 3.5, 64.0, .5, 90f, 0f)
            for (returning in listOf(false, true)) {
                var at = (if (returning) high else low).clone()
                val target = if (returning) low else high
                val route = CitizenPacketRoute(world, at, target,
                    { it.x in 0..3 && it.z == 0 && it.y in 63..65 }, { _, _ -> true }, { 1.0 })
                route.prepareSupportAdmission()
                route.failure shouldBe null
                repeat(200) {
                    route.move(at, 1.0) { true }?.let { next ->
                        citizenPacketBodyClear(world, next.point(), false) shouldBe true
                        at = next
                    }
                }
                route.failure shouldBe null
                route.finished shouldBe true
                at.distanceSquared(target) shouldBe 0.0
            }
        }
    }

    test("a half stair between full ground stays supported by the feet in both directions") {
        supportAdmissionWorld { world ->
            world.getBlockAt(0, 63, 0).blockData = Bukkit.createBlockData(
                "minecraft:cobblestone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            world.getBlockAt(0, 63, -1).type = Material.COBBLESTONE
            world.getBlockAt(0, 63, 1).type = Material.GRASS_BLOCK
            val outside = Location(world, .5, 64.0, 1.5)
            val inside = Location(world, .5, 64.0, -.5)
            for (returning in listOf(false, true)) for (speed in listOf(.55, 1.0)) {
                var at = (if (returning) inside else outside).clone()
                val target = if (returning) outside else inside
                val route = CitizenPacketRoute(world, at, target,
                    { it.x == 0 && it.z in -1..1 && it.y == 64 }, { _, _ -> true }, { 1.0 })
                route.prepareSupportAdmission()
                route.failure shouldBe null
                repeat(100) {
                    route.move(at, speed) { true }?.let { next ->
                        (at.distance(next) <= .215 * speed + .000001) shouldBe true
                        citizenPacketBodyClear(world, next.point(), false) shouldBe true
                        supportAdmissionFootContact(world, next) shouldBe true
                        citizenPacketStepClear(world, at.point(), next.point(), false) shouldBe true
                        at = next
                    }
                }
                route.failure shouldBe null
                route.finished shouldBe true
                at.distanceSquared(target) shouldBe 0.0
            }
        }
    }

    test("a half stair cannot carry a flat route over a missing adjacent full block") {
        supportAdmissionWorld { world ->
            world.getBlockAt(0, 63, 0).blockData = Bukkit.createBlockData(
                "minecraft:cobblestone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            for (z in listOf(-1, 1, 2)) world.getBlockAt(0, 63, z).type = Material.COBBLESTONE
            var at = Location(world, .5, 64.0, 2.5)
            val route = CitizenPacketRoute(world, at, Location(world, .5, 64.0, -.5),
                { it.x == 0 && it.z in -1..2 && it.y == 64 }, { _, _ -> true }, { 1.0 })
            route.prepareSupportAdmission()
            route.failure shouldBe null
            world.getBlockAt(0, 63, 1).type = Material.AIR
            repeat(100) { route.move(at, 1.0) { true }?.let { next -> at = next } }
            route.failure shouldBe "path-changed"
            route.finished shouldBe false
            (route.forwardProgress < 1.0) shouldBe true
        }
    }

    test("supported ground does not admit a route through blocked headroom") {
        supportAdmissionWorld { world ->
            world.getBlockAt(1, 64, 0).type = Material.OAK_PLANKS
            val route = CitizenPacketRoute(world, Location(world, .5, 63.0, .5), Location(world, 2.5, 63.0, .5),
                { it.x in 0..2 && it.z == 0 && it.y == 63 }, { _, _ -> true }, { 1.0 })
            route.prepareSupportAdmission()
            route.failure shouldBe "no-path"
            route.forwardProgress shouldBe 0.0
        }
    }

    test("an unloaded support chunk remains unavailable to the detached search") {
        supportAdmissionWorld { world ->
            for (x in 15..16) world.getBlockAt(x, 62, 0).type = Material.OAK_PLANKS
            world.unloadChunk(1, 0) shouldBe true
            val route = CitizenPacketRoute(world, Location(world, 15.5, 63.0, .5), Location(world, 16.5, 63.0, .5),
                { it.x in 15..16 && it.z == 0 && it.y == 63 }, { _, _ -> true }, { 1.0 })
            route.prepareSupportAdmission()
            route.failure shouldBe "chunk-unloaded"
            route.forwardProgress shouldBe 0.0
        }
    }
})

private fun supportAdmissionWorld(run: (CivsWorksiteWorldFixture) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("support-admission", ground = Material.AIR, collisionForData = { data ->
            when (data.material) {
                Material.ACACIA_SLAB -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0))
                Material.SPRUCE_FENCE -> listOf(BoundingBox(.375, 0.0, .375, .625, 1.5, .625))
                Material.BROWN_CARPET -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .0625, 1.0))
                // Native straight north-facing bottom stair, independent of any settlement layout.
                Material.COBBLESTONE_STAIRS -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                    BoundingBox(0.0, .5, 0.0, 1.0, 1.0, .5))
                Material.OAK_STAIRS -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                    if ((data as Stairs).facing == BlockFace.WEST) BoundingBox(0.0, .5, 0.0, .5, 1.0, 1.0)
                    else BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
                // Native south-facing halves on the two sides of the Auto1 warehouse seam.
                Material.CHEST -> if ((data as Chest).type == Chest.Type.LEFT)
                    listOf(BoundingBox(0.0, 0.0, .0625, .9375, .875, .9375))
                else listOf(BoundingBox(.0625, 0.0, .0625, 1.0, .875, .9375))
                else -> null
            }
        }).also(paper.server::addWorld)
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        for (x in -2..5) for (z in -5..2) world.getBlockAt(x, 62, z).type = Material.OAK_PLANKS
        run(world)
    }
}

private fun CitizenPacketRoute.prepareSupportAdmission() {
    repeat(1_000) { if (searching) expand() }
    searching shouldBe false
}

/** Independent native footprint oracle: a contact must intersect the actual sole, not just its centre. */
private fun supportAdmissionFootContact(world: CivsWorksiteWorldFixture, feet: Location): Boolean {
    val sole = BoundingBox(feet.x - .299, feet.y - .001, feet.z - .299,
        feet.x + .299, feet.y, feet.z + .299)
    val y = floor(sole.minY).toInt()
    return (floor(sole.minX).toInt()..floor(sole.maxX).toInt()).any { x ->
        (floor(sole.minZ).toInt()..floor(sole.maxZ).toInt()).any { z ->
            world.getBlockAt(x, y, z).collisionShape.boundingBoxes.any {
                it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()).overlaps(sole)
            }
        }
    }
}
