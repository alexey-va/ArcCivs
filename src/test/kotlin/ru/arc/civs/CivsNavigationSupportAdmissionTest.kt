package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsNavigationSupportAdmissionTest : FunSpec({
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
                Material.OAK_STAIRS -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0),
                    BoundingBox(.5, .5, 0.0, 1.0, 1.0, 1.0))
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
