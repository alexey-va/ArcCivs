package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import kotlin.math.abs

class CivsResidentCollisionsTest : FunSpec({
    val resident = BoundingBox(-.3, 64.0, -.3, .3, 65.8, .3)
    fun resolve(from: Vector, to: Vector, bodies: List<BoundingBox> = listOf(resident)) =
        citizenSlideBody(from, to, .3, 1.8, .3, bodies)
    fun near(actual: Double, expected: Double) { (abs(actual - expected) < .00001) shouldBe true }

    test("a whole crossing stops at the first body from either side and diagonal travel slides") {
        val forward = requireNotNull(resolve(Vector(-2.0, 64.0, 0.0), Vector(2.0, 64.0, 0.0)))
        near(forward.position.x, -.6)
        val reverse = requireNotNull(resolve(Vector(2.0, 64.0, 0.0), Vector(-2.0, 64.0, 0.0)))
        near(reverse.position.x, .6)
        val slide = requireNotNull(resolve(Vector(-2.0, 64.0, -.4), Vector(2.0, 64.0, 1.2)))
        near(slide.position.x, -.6)
        near(slide.position.z, 1.2)
        // The same actor can be passed once the player has walked around its side.
        resolve(slide.position, Vector(2.0, 64.0, 1.2)) shouldBe null
        val distantFirst = resident.clone().shift(2.0, 0.0, 0.0)
        val first = requireNotNull(resolve(Vector(-2.0, 64.0, 0.0), Vector(4.0, 64.0, 0.0),
            listOf(distantFirst, resident)))
        near(first.position.x, -.6)
    }

    test("spawn overlap allows an exit and tangential escape but cannot deepen or cross through the center") {
        val inside = Vector(.4, 64.0, 0.0)
        resolve(inside, Vector(1.5, 64.0, 0.0)) shouldBe null
        resolve(inside, Vector(.4, 64.0, 1.5)) shouldBe null
        val blocked = requireNotNull(resolve(inside, Vector(-1.5, 64.0, 0.0)))
        near(blocked.position.x, .4)
        resolve(Vector(0.0, 64.0, 0.0), Vector(-1.5, 64.0, 0.0)) shouldBe null
        // Grazing the exact contact plane is free, entering it is not.
        resolve(Vector(-.6, 64.0, -2.0), Vector(-.6, 64.0, 2.0)) shouldBe null
        val touching = requireNotNull(resolve(Vector(-.6, 64.0, 0.0), Vector(1.0, 64.0, 0.0)))
        near(touching.position.x, -.6)
    }

    test("packet resident steps and recovery probes respect full bodies while existing overlap can escape") {
        val terrain = mockk<World>()
        val npc = mockk<PacketPlayerNpc> {
            every { world } returns terrain
            every { scale } returns 1.0
            every { height } returns 1.8
        }
        fun clear(from: Location, to: Location) = citizenResidentStepClear(npc, from, to, listOf(resident))
        val left = Location(terrain, -1.0, 64.0, 0.0)
        clear(left, Location(terrain, 1.0, 64.0, 0.0)) shouldBe false
        val occupied = Location(terrain, 0.0, 64.0, 0.0)
        clear(occupied, occupied) shouldBe false
        clear(occupied, occupied.clone().add(.05, 0.0, 0.0)) shouldBe true
        val touching = Location(terrain, -.6, 64.0, 0.0)
        clear(touching, touching) shouldBe true
        clear(touching, touching.clone().add(0.0, 0.0, .2)) shouldBe true
        val above = Location(terrain, 0.0, 65.8, 0.0)
        clear(above, above) shouldBe true
        clear(above, above.clone().add(.2, 0.0, 0.0)) shouldBe true
        val child = mockk<PacketPlayerNpc> {
            every { world } returns terrain
            every { scale } returns .5
            every { height } returns .9
        }
        val childBeside = Location(terrain, -.45, 64.0, 0.0)
        citizenResidentStepClear(child, childBeside, childBeside, listOf(resident)) shouldBe true
    }

    test("Creative contact makes the resident yield without rewriting player movement or velocity") {
        collisionScenario { f ->
            f.mode = GameMode.CREATIVE
            val destination = Location(f.world, -.45, 64.0, 0.0, 83f, -12f)
            val event = f.move(destination)
            event.to shouldBe destination
            event.isCancelled shouldBe false
            near(f.npcAt.x, .05)
            f.velocity.x shouldBe 0.0
            f.velocity.y shouldBe .2
            f.velocity.z shouldBe 0.0
            repeat(20) { f.move(destination).to shouldBe destination }
            f.velocityWrites shouldBe 0
            f.nudgeAttempts shouldBe 1
            verify(exactly = 0) { f.player.teleport(any<Location>()) }
        }
    }

    test("repeated contact with two trapped residents preserves player momentum and movement across ticks") {
        collisionScenario { f ->
            f.allowNudge = false
            val momentum = Vector(.13, .2, -.07)
            f.velocity = momentum.clone()
            val second = mockk<PacketPlayerNpc> {
                every { uniqueId } returns UUID.randomUUID()
                every { isValid } returns true
                every { world } returns f.world
                every { pose } returns PacketNpcPose.STANDING
                every { height } returns 1.8
                every { scale } returns 1.0
                every { location } answers { Location(f.world, 0.0, 64.0, .15) }
            }
            f.residents = listOf(f.npc, second)
            val from = Location(f.world, -.8, 64.0, 0.0, 20f, 5f)
            val to = Location(f.world, -.4, 64.0, 0.0, 83f, -12f)
            repeat(20) {
                repeat(5) {
                    val event = f.move(to.clone(), from.clone())
                    event.from shouldBe from
                    event.to shouldBe to
                    event.isCancelled shouldBe false
                }
                f.playerAt = to.clone()
                f.owner.canResidentMove(f.npc, f.npcAt, f.npcAt.clone().add(-.2, 0.0, 0.0)) shouldBe false
                f.velocity shouldBe momentum
                f.tick()
            }
            f.velocityWrites shouldBe 0
            f.nudgeAttempts shouldBe 120 // Two residents, three safe probes, once per tick.
            f.npcAt shouldBe Location(f.world, 0.0, 64.0, 0.0)
            verify(exactly = 0) { f.player.velocity; f.player.teleport(any<Location>()) }
        }
    }

    test("an idle resident in a one-block doorway yields into free space without trapping the player") {
        collisionScenario { f ->
            f.npcAt = Location(f.world, .5, 64.0, .5)
            for (x in listOf(-1, 1)) for (y in 64..65) for (z in -1..2) f.walls += Triple(x, y, z)
            val destination = Location(f.world, .5, 64.0, .1)
            val event = f.move(destination, Location(f.world, .5, 64.0, -.7))
            event.to shouldBe destination
            event.isCancelled shouldBe false
            near(f.npcAt.z, .55)
            near(f.npcAt.x, .5)
            // Even a deeper overlap cannot become a server-side movement rollback.
            f.tick()
            val through = Location(f.world, .5, 64.0, .6)
            f.move(through, destination).to shouldBe through
            verify(exactly = 0) { f.player.teleport(any<Location>()) }
        }
    }

    test("a wall behind the resident permits a tangential nudge and no escape space still cannot block the player") {
        collisionScenario { f ->
            f.npcAt = Location(f.world, .5, 64.0, .7)
            for (y in 64..65) f.walls += Triple(0, y, 1)
            val destination = Location(f.world, .5, 64.0, .3)
            val event = f.move(destination, Location(f.world, .5, 64.0, -.4))
            event.to shouldBe destination
            event.isCancelled shouldBe false
            near(f.npcAt.z, .7) // The .05 forward step would enter the real rear wall.
            near(abs(f.npcAt.x - .5), .05)
            f.nudgeAttempts shouldBe 2
            f.tick()
            f.allowNudge = false
            val trapped = f.npcAt.clone()
            val closer = Location(f.world, .5, 64.0, .6)
            f.move(closer, destination).to shouldBe closer
            f.npcAt shouldBe trapped
            f.nudgeAttempts shouldBe 5 // One forward and two side probes; never a larger correction.
            f.velocityWrites shouldBe 0
            verify(exactly = 0) { f.player.teleport(any<Location>()) }
        }
    }

    test("look-only canceled teleported other-world and nonparticipating moves stay untouched") {
        collisionScenario { f ->
            fun free() {
                val destination = Location(f.world, -.4, 64.0, 0.0)
                f.move(destination).to shouldBe destination
            }
            f.mode = GameMode.SPECTATOR; free(); f.mode = GameMode.SURVIVAL
            f.vehicle = true; free(); f.vehicle = false
            f.sleeping = true; free(); f.sleeping = false
            f.collidable = false; free(); f.collidable = true
            f.move(Location(f.world, -.4, 64.0, 0.0), canceled = true).to!!.x shouldBe -.4
            val teleport = PlayerTeleportEvent(f.player, Location(f.world, -2.0, 64.0, 0.0),
                Location(f.world, -.4, 64.0, 0.0), PlayerTeleportEvent.TeleportCause.PLUGIN)
            f.owner.onMove(teleport)
            teleport.to!!.x shouldBe -.4
            val elsewhere = Location(mockk<World>(), -.4, 64.0, 0.0)
            f.move(elsewhere).to shouldBe elsewhere
            val reads = f.providerReads
            repeat(20) { f.move(Location(f.world, -2.0, 64.0, 0.0, it.toFloat(), 0f)) }
            f.providerReads shouldBe reads
            f.owner.close()
            free()
            f.providerReads shouldBe reads
            f.velocityWrites shouldBe 0
            f.nudgeAttempts shouldBe 0
        }
    }

    test("crouch and native player height apply while sleeping or retired residents cause no push") {
        collisionScenario { f ->
            f.npcPose = PacketNpcPose.CROUCHING
            f.npcHeight = 1.5
            f.playerHeight = .6
            f.move(Location(f.world, -.4, 65.6, 0.0), Location(f.world, -2.0, 65.6, 0.0))
            f.velocityWrites shouldBe 0
            f.nudgeAttempts shouldBe 0
            f.move(Location(f.world, -.4, 64.0, 0.0))
            f.nudgeAttempts shouldBe 1
            f.tick()
            f.npcPose = PacketNpcPose.SLEEPING
            f.move(Location(f.world, -.4, 64.0, 0.0))
            f.npcPose = PacketNpcPose.STANDING
            f.npcValid = false
            f.move(Location(f.world, -.4, 64.0, 0.0))
            f.velocityWrites shouldBe 0
            f.nudgeAttempts shouldBe 1
        }
    }

    test("the bounded 48-resident roster includes the last actor without a native entity scan") {
        collisionScenario { f ->
            val distant = List(47) { index ->
                mockk<PacketPlayerNpc> {
                    every { isValid } returns true
                    every { world } returns f.world
                    every { pose } returns PacketNpcPose.STANDING
                    every { height } returns 1.8
                    every { scale } returns 1.0
                    every { location } returns Location(f.world, 100.0 + index, 64.0, 0.0)
                }
            }
            f.residents = distant + f.npc
            f.move(Location(f.world, -.4, 64.0, 0.0)).to!!.x shouldBe -.4
            f.velocityWrites shouldBe 0
            f.nudgeAttempts shouldBe 1
            f.providerReads shouldBe 1
            verify(exactly = 0) { f.world.entities; f.world.players }
        }
    }

    test("a child has a scaled contact body and can be nudged below an adult-height obstacle") {
        collisionScenario { f ->
            f.npcScale = .5
            f.npcHeight = .9
            f.move(Location(f.world, -.5, 64.0, 0.0))
            f.move(Location(f.world, -.3, 65.0, 0.0), Location(f.world, -2.0, 65.0, 0.0))
            f.velocityWrites shouldBe 0
            f.playerAt = Location(f.world, .5, 64.0, 0.0)
            val at = Location(f.world, 0.0, 64.0, 0.0)
            f.owner.canResidentMove(f.npc, at, at) shouldBe true
            f.walls += Triple(0, 65, 0)
            citizenResidentNudgeClear(f.npc, at, at.clone().add(.05, 0.0, 0.0)) shouldBe true
            f.npcScale = 1.0
            f.npcHeight = 1.8
            citizenResidentNudgeClear(f.npc, at, at.clone().add(.05, 0.0, 0.0)) shouldBe false
            f.owner.canResidentMove(f.npc, at, at) shouldBe false
        }
    }

    test("resident walking yields to a nearby player and preserves its waypoint and read-only recovery occupancy") {
        collisionScenario { f ->
            f.mode = GameMode.CREATIVE
            f.playerAt = Location(f.world, .7, 64.0, 0.0)
            val from = Location(f.world, 0.0, 64.0, 0.0)
            val to = Location(f.world, .2, 64.0, 0.0)
            f.owner.canResidentMove(f.npc, from, to) shouldBe false
            f.velocityWrites shouldBe 0
            f.velocity shouldBe Vector(0.0, .2, 0.0)
            near(f.npcAt.x, -.05)
            f.owner.canResidentMove(f.npc, f.playerAt, f.playerAt) shouldBe false
            f.velocityWrites shouldBe 0
            f.mode = GameMode.SPECTATOR
            f.owner.canResidentMove(f.npc, from, to) shouldBe true
            f.mode = GameMode.SURVIVAL
            f.playerAt = Location(f.world, 1.0, 64.0, 3.0)
            f.owner.canResidentMove(f.npc, from, to) shouldBe true
            f.owner.canResidentMove(f.npc, from, Location(f.world, 30.0, 64.0, 0.0)) shouldBe false
            verify(exactly = 0) { f.world.entities; f.world.players }
        }
    }

    test("a nudge cannot cross an unloaded chunk or invent support over a drop") {
        collisionScenario { f ->
            val from = f.npcAt.clone()
            val to = from.clone().add(.05, 0.0, 0.0)
            f.loaded = false
            citizenResidentNudgeClear(f.npc, from, to) shouldBe false
            f.blockReads shouldBe 0
            f.loaded = true
            f.supported = false
            citizenResidentNudgeClear(f.npc, from, to) shouldBe false
            citizenResidentNudgeClear(f.npc, from, from.clone().add(.1, 0.0, 0.0)) shouldBe false
        }
    }

    test("new native overlap exits in supported short steps while ordinary nudges remain blocked") {
        collisionScenario { f ->
            f.npcAt = Location(f.world, .4, 64.0, .5)
            f.walls += Triple(0, 65, 0)
            citizenResidentNudgeClear(f.npc, f.npcAt, f.npcAt.clone().add(-.05, 0.0, 0.0)) shouldBe false
            var steps = 0
            repeat(20) {
                val result = citizenResidentEscapeStep(f.npc) { true } ?: return@repeat
                result.obstruction shouldBe CivPosition(0, 65, 0)
                val next = requireNotNull(result.step)
                (f.npcAt.distance(next) <= .050001) shouldBe true
                (next.x < f.npcAt.x) shouldBe true
                next.y shouldBe f.npcAt.y
                f.npcAt = next
                steps++
            }
            (steps in 1..20) shouldBe true
            citizenPacketBodyClear(f.world, CitizenPoint(f.npcAt.x, f.npcAt.y, f.npcAt.z), false) shouldBe true
            f.walls shouldBe setOf(Triple(0, 65, 0))
        }
    }

    test("native overlap cannot deepen enter a new solid or bypass denied unsupported and unloaded exits") {
        collisionScenario { f ->
            f.npcAt = Location(f.world, .4, 64.0, .5)
            f.walls += Triple(0, 65, 0)
            // Only the deeper direction is authorized; it cannot become an exit.
            requireNotNull(citizenResidentEscapeStep(f.npc) { it.x > f.npcAt.x }).step shouldBe null
            requireNotNull(citizenResidentEscapeStep(f.npc) { false }).step shouldBe null
            f.npcAt.x = .315
            f.walls += Triple(-1, 65, 0)
            requireNotNull(citizenResidentEscapeStep(f.npc) { it.x < f.npcAt.x }).step shouldBe null
            f.walls -= Triple(-1, 65, 0)
            f.supported = false
            requireNotNull(citizenResidentEscapeStep(f.npc) { true }).step shouldBe null
            f.supported = true
            f.loaded = false
            f.blockReads = 0
            citizenResidentEscapeStep(f.npc) { true } shouldBe null
            f.blockReads shouldBe 0
            f.npcAt shouldBe Location(f.world, .315, 64.0, .5)
        }
    }

    test("native overlap recovery uses the actual scaled body and leaves clear children in place") {
        collisionScenario { f ->
            f.npcAt = Location(f.world, .5, 64.0, .5)
            f.walls += Triple(0, 65, 0)
            f.npcScale = .5
            f.npcHeight = .9
            citizenResidentEscapeStep(f.npc) { true } shouldBe null
        }
    }
})

private fun collisionScenario(block: (ResidentCollisionFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper -> ResidentCollisionFixture(paper).use(block) }
    }
}

/** Native event wiring and real voxel geometry; this does not claim client prediction QA. */
private class ResidentCollisionFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = mockk<World>()
    val player = mockk<Player>()
    val npc = mockk<PacketPlayerNpc>()
    var residents: Collection<PacketPlayerNpc> = listOf(npc)
    var providerReads = 0
    var mode = GameMode.SURVIVAL
    var vehicle = false
    var sleeping = false
    var collidable = true
    var npcValid = true
    var npcPose = PacketNpcPose.STANDING
    var npcHeight = 1.8
    var npcScale = 1.0
    var playerHeight = 1.8
    var playerAt = Location(world, -2.0, 64.0, 0.0)
    var npcAt = Location(world, 0.0, 64.0, 0.0)
    var velocity = Vector(0.0, .2, 0.0)
    var velocityWrites = 0
    var nudgeAttempts = 0
    var allowNudge = true
    var loaded = true
    var supported = true
    var blockReads = 0
    val walls = hashSetOf<Triple<Int, Int, Int>>()
    val owner: CivsResidentCollisions

    init {
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.location } answers { playerAt.clone() }
        every { player.isOnline } returns true
        every { player.isValid } returns true
        every { player.isDead } returns false
        every { player.isInsideVehicle } answers { vehicle }
        every { player.isSleeping } answers { sleeping }
        every { player.isCollidable } answers { collidable }
        every { player.gameMode } answers { mode }
        every { player.velocity } answers { velocity.clone() }
        every { player.velocity = any() } answers { velocityWrites++; velocity = firstArg<Vector>().clone() }
        every { player.boundingBox } answers {
            BoundingBox(playerAt.x - .3, playerAt.y, playerAt.z - .3,
                playerAt.x + .3, playerAt.y + playerHeight, playerAt.z + .3)
        }
        every { npc.uniqueId } returns UUID.randomUUID()
        every { npc.isValid } answers { npcValid }
        every { npc.world } returns world
        every { npc.location } answers { npcAt.clone() }
        every { npc.teleport(any()) } answers { npcAt = firstArg<Location>().clone() }
        every { npc.pose } answers { npcPose }
        every { npc.height } answers { npcHeight }
        every { npc.scale } answers { npcScale }
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        val chunk = mockk<Chunk> { every { loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING }
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
        val blocks = hashMapOf<Triple<Int, Int, Int>, Block>()
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            blockReads++
            val key = Triple(firstArg<Int>(), secondArg<Int>(), thirdArg<Int>())
            blocks.getOrPut(key) {
                mockk<Block> {
                    every { type } answers { if (key in walls || supported && key.second <= 63) Material.STONE else Material.AIR }
                    every { blockData } answers { Bukkit.createBlockData(type) }
                    every { isLiquid } returns false
                    every { collisionShape } answers { if (type.isAir) EMPTY else FULL }
                }
            }
        }
        every { world.getNearbyPlayers(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns listOf(player)
        owner = CivsResidentCollisions(paper.createSimplePlugin("ResidentCollisions"), actors = {
            providerReads++
            residents
        }, nudgeResident = { actor, impulse ->
            nudgeAttempts++
            val from = actor.location
            val to = from.clone().add(impulse)
            if (allowNudge && citizenResidentNudgeClear(actor, from, to)) { actor.teleport(to); true } else false
        })
    }

    fun move(to: Location, from: Location = Location(world, -2.0, 64.0, 0.0), canceled: Boolean = false): PlayerMoveEvent {
        playerAt = from.clone()
        val event = PlayerMoveEvent(player, from, to)
        event.isCancelled = canceled
        paper.server.pluginManager.callEvent(event)
        return event
    }

    fun tick() = paper.performTicks(1)
    override fun close() = owner.close()

    companion object {
        private fun shape(boxes: List<BoundingBox>) = object : VoxelShape {
            override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
            override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
        }
        private val EMPTY = shape(emptyList())
        private val FULL = shape(listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
    }
}
