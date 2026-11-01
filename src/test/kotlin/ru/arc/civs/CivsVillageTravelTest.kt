package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.VoxelShape
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsVillageTravelTest : FunSpec({
    test("public visits preload existing terrain then reach the autonomous hall without changing membership") {
        MockBukkitTestRuntime.open().use { runtime ->
            val h = VillageTravelScenario(runtime)
            val before = h.city
            h.loaded = false
            val arrival = h.travel.visit(h.player, h.city.id)
            h.loads.size shouldBe 4
            h.teleports.size shouldBe 0
            arrival.isDone shouldBe false
            h.loaded = true
            h.loads.forEach { it.complete(mockk()) }
            h.scheduler.executeAll()
            arrival.join() shouldBe Unit
            h.teleports.single().let { at ->
                at.world shouldBe h.world
                at.x shouldBe .5
                at.y shouldBe 64.0
                at.z shouldBe .5
            }
            h.city shouldBe before
            h.city.canManage(h.player.uniqueId) shouldBe false
            h.tasks.close()
        }
    }

    test("personal cities and visitors without the public permission cannot use the travel boundary") {
        MockBukkitTestRuntime.open().use { runtime ->
            val h = VillageTravelScenario(runtime)
            h.city = h.city.copy(autonomy = null)
            shouldThrow<CivsRejection> { h.travel.visit(h.player, h.city.id) }.reason shouldBe "not-authorized"
            h.city = h.city.copy(autonomy = CivAutonomy())
            h.attachment.setPermission("arccivs.use", false)
            shouldThrow<CivsRejection> { h.travel.visit(h.player, h.city.id) }.reason shouldBe "not-authorized"
            h.teleports.size shouldBe 0
            h.loads.size shouldBe 0
            h.tasks.close()
        }
    }

    test("late completions reject deleted stale offline and unsafe destinations and a cancelled native teleport") {
        for (change in listOf("deletion", "hall", "session", "unsafe", "cancelled")) {
            MockBukkitTestRuntime.open().use { runtime ->
                val h = VillageTravelScenario(runtime)
                h.loaded = false
                val arrival = h.travel.visit(h.player, h.city.id)
                when (change) {
                    "deletion" -> h.city = h.city.copy(pendingDeletion = CivDeletion(null))
                    "hall" -> h.city = h.city.copy(buildings = h.city.buildings.map { it.copy(entrance = CivPosition(1, 64, 0)) })
                    "session" -> h.connected = null
                    "unsafe" -> h.obstructed = true
                    "cancelled" -> h.teleportAccepted = false
                }
                h.loaded = true
                h.loads.forEach { it.complete(mockk()) }
                h.scheduler.executeAll()
                val failure = shouldThrow<CompletionException> { arrival.join() }.cause as CivsRejection
                failure.reason shouldBe when (change) {
                    "deletion" -> "deletion-pending"
                    "hall" -> "stale-city"
                    "session" -> "player-offline"
                    "unsafe" -> "visit-unavailable"
                    else -> "visit-failed"
                }
                h.teleports.size shouldBe if (change == "cancelled") 1 else 0
                h.tasks.close()
            }
        }
    }
})

private class VillageTravelScenario(runtime: MockBukkitTestRuntime) {
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val player = runtime.addPlayer("Visitor")
    val attachment = player.addAttachment(runtime.createSimplePlugin("VillageTravel"), "arccivs.use", true)
    val world = mockk<World>()
    private val worldId = UUID.randomUUID()
    private val plugin = mockk<Plugin>()
    private val server = mockk<Server>()
    private val border = mockk<WorldBorder>()
    var city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Village", worldId, CivPosition(0, 64, 0), 32,
        "medieval", Stock(), listOf(CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1,
            CivPosition(3, 64, 3), 0, "hall", CivBox(CivPosition(3, 64, 3), CivPosition(5, 67, 5)),
            CivPosition(0, 64, 0), BuildingPhase.ACTIVE)), emptyList(), autonomy = CivAutonomy())
    var loaded = true
    var obstructed = false
    var connected: Player? = player
    var teleportAccepted = true
    val loads = mutableListOf<CompletableFuture<Chunk?>>()
    val teleports = mutableListOf<Location>()
    private val air = block(Material.AIR, emptyList())
    private val floor = block(Material.OAK_PLANKS, listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
    val travel: CivsVillageTravel

    init {
        every { plugin.server } returns server
        every { server.getWorld(worldId) } returns world
        every { server.getPlayer(player.uniqueId) } answers { connected }
        every { world.uid } returns worldId
        every { world.minHeight } returns -64
        every { world.maxHeight } returns 320
        every { world.worldBorder } returns border
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { border.isInside(any<Location>()) } returns true
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val y = secondArg<Int>()
            if (y == 63 || obstructed && y in 64..65) floor else air
        }
        val records = CivsCityRecords({ mapOf(city.id to city) }, emptySet(), emptySet(), { false },
            { false }, { null }, { 0 }, { 0 })
        travel = CivsVillageTravel(plugin, tasks, records, { true }, load = { _, _, _ ->
            CompletableFuture<Chunk?>().also(loads::add)
        }, teleport = { _, at -> teleports += at.clone(); teleportAccepted })
    }

    private fun block(material: Material, boxes: List<BoundingBox>): Block = mockk<Block>().also { block ->
        val shape = mockk<VoxelShape>()
        every { block.type } returns material
        every { block.isLiquid } returns false
        every { block.collisionShape } returns shape
        every { shape.boundingBoxes } returns boxes
    }
}
