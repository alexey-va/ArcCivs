package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsCorpsePlacementTest : FunSpec({
    test("airborne death settles on a full floor and preserves its saved death identity") {
        corpseFloor { world ->
            val corpse = placementCorpse(y = 77, feetY = 77.75)
            val body = requireNotNull(civsCorpsePlacement(world, corpse))
            body.anchor.y shouldBe 64.0
            body.bounds.minY shouldBe (64.02 plusOrMinus 1e-6)
            civsCorpsePlacementValid(body) shouldBe true
            civsCorpseStandingSafe(body.anchor) shouldBe true
            corpse.position.y shouldBe 77
            corpse.feetY shouldBe 77.75
        }
    }

    test("the body follows the real fractional path collision top") {
        corpseFloor { world ->
            for (x in -4..4) for (z in -4..4) world.getBlockAt(x, 63, z).type = Material.DIRT_PATH
            val body = requireNotNull(civsCorpsePlacement(world, placementCorpse(y = 63, feetY = 63.9375)))
            body.anchor.y shouldBe 63.9375
            body.bounds.minY shouldBe (63.9575 plusOrMinus 1e-6)
            civsCorpsePlacementValid(body) shouldBe true
        }
    }

    test("native wall clearance rotates the full sleeping model and includes armour width") {
        corpseFloor { world ->
            world.getBlockAt(-2, 64, 0).type = Material.STONE
            val body = requireNotNull(civsCorpsePlacement(world, placementCorpse()))
            body.feet.yaw shouldBe 90f
            body.bounds.widthX shouldBe (1.24 plusOrMinus 1e-6)
            body.bounds.widthZ shouldBe (2.03 plusOrMinus 1e-6)
            civsCorpsePlacementValid(body) shouldBe true
        }
    }

    test("an unsupported body end chooses a fully supported lower neighbouring floor") {
        corpseFloor { world ->
            // One surviving elevated block cannot support the adult's head, torso and feet.
            for (x in -4..4) for (z in -4..4) if (x != 0 || z != 0)
                world.getBlockAt(x, 63, z).type = Material.AIR
            val body = requireNotNull(civsCorpsePlacement(world, placementCorpse()))
            body.anchor.y shouldBe 63.0
            civsCorpsePlacementValid(body) shouldBe true
        }
    }

    test("new solid blocks and removed support invalidate the exact accepted resurrection point") {
        corpseFloor { world ->
            val at = Location(world, .5, 64.0, .5)
            civsCorpseStandingSafe(at) shouldBe true
            world.getBlockAt(0, 64, 0).type = Material.STONE
            civsCorpseStandingSafe(at) shouldBe false
            world.getBlockAt(0, 64, 0).type = Material.AIR
            world.getBlockAt(0, 63, 0).type = Material.AIR
            civsCorpseStandingSafe(at) shouldBe false
        }
    }

    test("loaded placement bounds reject an enclosed body and never load a remote chunk") {
        corpseFloor { world ->
            for (x in -4..4) for (z in -4..4) for (y in 64..66)
                world.getBlockAt(x, y, z).type = Material.STONE
            civsCorpsePlacement(world, placementCorpse()) shouldBe null
            val remote = placementCorpse().copy(position = CivPosition(256, 64, 256))
            world.isChunkLoaded(16, 16) shouldBe false
            civsCorpsePlacement(world, remote) shouldBe null
            world.isChunkLoaded(16, 16) shouldBe false
        }
    }

    test("resurrection cannot use damaging support or noncolliding fire in standing headroom") {
        corpseFloor { world ->
            val at = Location(world, .5, 64.0, .5)
            world.getBlockAt(0, 63, 0).type = Material.MAGMA_BLOCK
            civsCorpseStandingSafe(at) shouldBe false
            world.getBlockAt(0, 63, 0).type = Material.STONE
            world.getBlockAt(0, 65, 0).type = Material.FIRE
            civsCorpseStandingSafe(at) shouldBe false
            val body = requireNotNull(civsCorpsePlacement(world, placementCorpse()))
            (body.anchor.x == at.x && body.anchor.z == at.z) shouldBe false
            civsCorpsePlacementValid(body) shouldBe true
        }
    }

    test("a drop beyond the bounded search stays hidden instead of floating or forcing terrain") {
        corpseFloor { world ->
            civsCorpsePlacement(world, placementCorpse(y = 100)) shouldBe null
            world.getBlockAt(0, 63, 0).type shouldBe Material.STONE
        }
    }
})

private fun placementCorpse(y: Int = 64, feetY: Double = y.toDouble()) = CivsCorpses.create(
    CivWorker(UUID(0, 91), "Анна", null, sex = CivSex.FEMALE),
    CivDeathContext(CivPosition(0, y, 0), 10_000L, cause = "FALL", feetY = feetY))

private fun corpseFloor(block: (CivsWorksiteWorldFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        val world = CivsWorksiteWorldFixture("corpse-floor").also(paper.server::addWorld)
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        block(world)
    }
}
