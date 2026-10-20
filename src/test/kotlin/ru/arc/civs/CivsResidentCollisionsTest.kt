package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
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

    test("Creative walking is corrected through the registered move event while view and native walls are respected") {
        collisionScenario { f ->
            f.mode = GameMode.CREATIVE
            val event = f.move(Location(f.world, 2.0, 64.0, 1.2, 83f, -12f),
                from = Location(f.world, -2.0, 64.0, -.4))
            near(event.to!!.x, -.6)
            near(event.to!!.z, 1.2)
            event.to!!.yaw shouldBe 83f
            event.to!!.pitch shouldBe -12f
            event.isCancelled shouldBe false
            f.wall = true
            val besideWall = f.move(Location(f.world, 2.0, 64.0, 1.2), from = Location(f.world, -2.0, 64.0, -.4))
            near(besideWall.to!!.x, -.6)
            (besideWall.to!!.z < .2) shouldBe true
            verify(exactly = 0) { f.player.teleport(any<Location>()) }
        }
    }

    test("look-only canceled teleported other-world spectator vehicle and sleeping moves stay untouched") {
        collisionScenario { f ->
            fun free() {
                val destination = Location(f.world, 2.0, 64.0, 0.0)
                f.move(destination).to shouldBe destination
            }
            f.mode = GameMode.SPECTATOR; free(); f.mode = GameMode.SURVIVAL
            f.vehicle = true; free(); f.vehicle = false
            f.sleeping = true; free(); f.sleeping = false
            f.move(Location(f.world, 2.0, 64.0, 0.0), canceled = true).to!!.x shouldBe 2.0
            val teleport = PlayerTeleportEvent(f.player, Location(f.world, -2.0, 64.0, 0.0),
                Location(f.world, 2.0, 64.0, 0.0), PlayerTeleportEvent.TeleportCause.PLUGIN)
            f.owner.onMove(teleport)
            teleport.to!!.x shouldBe 2.0
            val elsewhere = Location(mockk<World>(), 2.0, 64.0, 0.0)
            f.move(elsewhere).to shouldBe elsewhere
            val reads = f.providerReads
            repeat(20) {
                f.move(Location(f.world, -2.0, 64.0, 0.0, it.toFloat(), 0f)).to!!.x shouldBe -2.0
            }
            f.providerReads shouldBe reads
            f.owner.close()
            free()
            f.providerReads shouldBe reads
        }
    }

    test("current crouch height and player body height apply and sleeping or retired residents do not block") {
        collisionScenario { f ->
            f.npcPose = PacketNpcPose.CROUCHING
            f.npcHeight = 1.5
            f.playerHeight = .6 // Crawling/swimming dimensions come from the native player box.
            val above = Location(f.world, 2.0, 65.6, 0.0)
            f.move(above, from = Location(f.world, -2.0, 65.6, 0.0)).to shouldBe above
            near(f.move(Location(f.world, 2.0, 64.0, 0.0)).to!!.x, -.6)
            f.npcPose = PacketNpcPose.SLEEPING
            f.move(Location(f.world, 2.0, 64.0, 0.0)).to!!.x shouldBe 2.0
            f.npcPose = PacketNpcPose.STANDING
            f.npcValid = false
            f.move(Location(f.world, 2.0, 64.0, 0.0)).to!!.x shouldBe 2.0
        }
    }

    test("the full 48-resident roster is bounded and the last actor can stop a player") {
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
            near(f.move(Location(f.world, 2.0, 64.0, 0.0)).to!!.x, -.6)
            f.providerReads shouldBe 1
            verify(exactly = 0) { f.world.entities; f.world.players }
        }
    }

    test("a child uses its scaled width and height instead of an invisible adult body") {
        collisionScenario { f ->
            f.npcScale = .5
            f.npcHeight = .9
            near(f.move(Location(f.world, 2.0, 64.0, 0.0)).to!!.x, -.45)
            val above = Location(f.world, 2.0, 65.0, 0.0)
            f.move(above, from = Location(f.world, -2.0, 65.0, 0.0)).to shouldBe above
            f.playerAt = Location(f.world, .5, 64.0, 0.0)
            val at = Location(f.world, 0.0, 64.0, 0.0)
            f.owner.canResidentMove(f.npc, at, at) shouldBe true
            f.npcScale = 1.0
            f.npcHeight = 1.8
            f.owner.canResidentMove(f.npc, at, at) shouldBe false
        }
    }

    test("resident movement waits for a nearby Creative player and recovery cannot teleport into an occupied body") {
        collisionScenario { f ->
            f.mode = GameMode.CREATIVE
            f.playerAt = Location(f.world, 1.0, 64.0, 0.0)
            val from = Location(f.world, 0.0, 64.0, 0.0)
            val to = Location(f.world, 1.5, 64.0, 0.0)
            f.owner.canResidentMove(f.npc, from, to) shouldBe false
            f.owner.canResidentMove(f.npc, f.playerAt, f.playerAt) shouldBe false
            f.mode = GameMode.SPECTATOR
            f.owner.canResidentMove(f.npc, from, to) shouldBe true
            f.mode = GameMode.SURVIVAL
            f.playerAt = Location(f.world, 1.0, 64.0, 3.0)
            f.owner.canResidentMove(f.npc, from, to) shouldBe true
            f.owner.canResidentMove(f.npc, from, Location(f.world, 30.0, 64.0, 0.0)) shouldBe false
            verify(exactly = 0) { f.world.entities; f.world.players }
        }
    }
})

private fun collisionScenario(block: (ResidentCollisionFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        ResidentCollisionFixture(paper).use(block)
    }
}

/** Tests Paper event wiring and explicit geometry, not client-side prediction or smoothness. */
private class ResidentCollisionFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = mockk<World>()
    val player = mockk<Player>()
    val npc = mockk<PacketPlayerNpc>()
    var residents: Collection<PacketPlayerNpc> = listOf(npc)
    var providerReads = 0
    var mode = GameMode.SURVIVAL
    var vehicle = false
    var sleeping = false
    var npcValid = true
    var npcPose = PacketNpcPose.STANDING
    var npcHeight = 1.8
    var npcScale = 1.0
    var playerHeight = 1.8
    var playerAt = Location(world, -2.0, 64.0, 0.0)
    var wall = false
    val owner: CivsResidentCollisions

    init {
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.isValid } returns true
        every { player.isDead } returns false
        every { player.isInsideVehicle } answers { vehicle }
        every { player.isSleeping } answers { sleeping }
        every { player.gameMode } answers { mode }
        every { player.boundingBox } answers {
            BoundingBox(playerAt.x - .3, playerAt.y, playerAt.z - .3,
                playerAt.x + .3, playerAt.y + playerHeight, playerAt.z + .3)
        }
        every { player.wouldCollideUsing(any()) } answers { wall }
        every { npc.isValid } answers { npcValid }
        every { npc.world } returns world
        every { npc.location } answers { Location(world, 0.0, 64.0, 0.0) }
        every { npc.pose } answers { npcPose }
        every { npc.height } answers { npcHeight }
        every { npc.scale } answers { npcScale }
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { world.getNearbyPlayers(any<Location>(), any<Double>(), any<Double>(), any<Double>()) } returns listOf(player)
        owner = CivsResidentCollisions(paper.createSimplePlugin("ResidentCollisions")) {
            providerReads++
            residents
        }
    }

    fun move(to: Location, from: Location = Location(world, -2.0, 64.0, 0.0), canceled: Boolean = false): PlayerMoveEvent {
        val event = PlayerMoveEvent(player, from, to)
        event.isCancelled = canceled
        paper.server.pluginManager.callEvent(event)
        return event
    }

    override fun close() = owner.close()
}
