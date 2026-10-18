package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsBoundaryRendererTest : FunSpec({
    test("border follows the outer faces of inclusive territory without integer overflow") {
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID())
        civsBoundaryBounds(city) shouldBe CivsBoundaryBounds(-96, 97, -96, 97)
        val shifted = city.copy(center = CivPosition(-123, 64, -67), radius = 128)
        civsBoundaryBounds(shifted) shouldBe CivsBoundaryBounds(-251, 6, -195, 62)
        shifted.contains(5, 61) shouldBe true
        shifted.contains(6, 61) shouldBe false
        civsBoundaryBounds(city.copy(center = CivPosition(Int.MAX_VALUE, 64, Int.MIN_VALUE), radius = Int.MAX_VALUE)) shouldBe
            CivsBoundaryBounds(0, 4_294_967_295L, -4_294_967_295L, 0)
    }

    test("only nearby fixed segments are sampled and cost does not follow the territory area") {
        val city = boundaryCity(UUID.randomUUID(), UUID.randomUUID())
        civsBoundaryGeometry(city, 0.5, 64.0, 0.5) shouldBe emptyList()
        val edge = civsBoundaryGeometry(city, 85.5, 64.0, 0.5)
        edge.size shouldBe 15
        edge.map { it.key.side }.toSet() shouldBe setOf(CivsBoundarySide.EAST)
        civsBoundaryGeometry(city, 86.5, 64.4, 1.5) shouldBe edge
        for (radius in listOf(1, 96, 128, 1_000_000, Int.MAX_VALUE)) {
            val candidate = city.copy(radius = radius)
            val parts = civsBoundaryGeometry(candidate, radius - 4.0, 125.0, radius - 4.0)
            (parts.size <= 60) shouldBe true
            parts.map { it.key }.distinct().size shouldBe parts.size
            parts.all { it.cuboid.sx > 0 && it.cuboid.sy > 0 && it.cuboid.sz > 0 } shouldBe true
            parts.all { it.cuboid.y == 125.12 } shouldBe true
        }
    }

    test("corner posts are unique and bars join without overlapping coplanar faces") {
        val parts = civsBoundaryGeometry(boundaryCity(UUID.randomUUID(), UUID.randomUUID()), 89.0, 64.0, 89.0)
        parts.map { it.key.side }.toSet() shouldBe setOf(CivsBoundarySide.EAST, CivsBoundarySide.SOUTH)
        val posts = parts.filter { it.key.post }.map { it.cuboid.x to it.cuboid.z }
        posts.distinct().size shouldBe posts.size
        for ((index, a) in parts.withIndex()) for (b in parts.drop(index + 1)) {
            val x = minOf(a.cuboid.x + a.cuboid.sx, b.cuboid.x + b.cuboid.sx) - maxOf(a.cuboid.x, b.cuboid.x)
            val y = minOf(a.cuboid.y + a.cuboid.sy, b.cuboid.y + b.cuboid.sy) - maxOf(a.cuboid.y, b.cuboid.y)
            val z = minOf(a.cuboid.z + a.cuboid.sz, b.cuboid.z + b.cuboid.sz) - maxOf(a.cuboid.z, b.cuboid.z)
            (x > 0.00001 && y > 0.00001 && z > 0.00001) shouldBe false
        }
    }

    test("stationary membership retains private packet handles and leaving the edge removes them") {
        boundaryScenario { h ->
            val nativeEntities = h.player.world.entities.size
            h.renderer.pulse(h.player, h.city)
            h.handles.size shouldBe 0
            h.paper.performTicks(2)
            h.handles.size shouldBe 15
            h.player.world.entities.size shouldBe nativeEntities
            h.handles.forEach { handle ->
                verify(exactly = 1) { handle.isVisibleByDefault = false; handle.showTo(h.player) }
                verify(exactly = 0) { handle.teleport(any()) }
            }
            repeat(4) { h.renderer.pulse(h.player, h.city); h.paper.performTicks(2) }
            h.handles.size shouldBe 15
            h.handles.forEach { verify(exactly = 0) { it.teleport(any()) } }
            h.player.teleport(Location(h.player.world, 0.5, 64.0, 0.5))
            h.renderer.pulse(h.player, h.city)
            h.paper.performTicks(2)
            h.remaining.size shouldBe 0
            h.renderer.clear(h.player.uniqueId)
            h.handles.forEach { verify(exactly = 1) { it.remove() } }
        }
    }

    test("shared native budget bounds concurrent viewers and coalesces an obsolete boundary") {
        boundaryScenario(budget = 2) { h ->
            val member = h.paper.addPlayer("Member").apply { teleport(h.player.location) }
            val city = h.city.copy(members = setOf(member.uniqueId))
            h.renderer.pulse(h.player, city)
            h.renderer.pulse(member, city)
            h.paper.performTicks(1)
            h.handles.size shouldBe 2
            h.renderer.pulse(h.player, city.copy(radius = 128)) // New edge is too far away; retire the partial scene.
            h.paper.performTicks(30)
            h.remaining.size shouldBe 15
            h.handles.filter { it in h.remaining }.forEach { verify(exactly = 1) { it.showTo(member) } }
            h.renderer.pulse(member, city.copy(members = emptySet()))
            h.paper.performTicks(20)
            h.remaining.size shouldBe 0
        }
    }

    test("world changes clear private scenes and close retains the display owner's actual drain") {
        boundaryScenario { h ->
            h.renderer.pulse(h.player, h.city)
            h.paper.performTicks(2)
            val other = h.paper.addSimpleWorld("other-boundary")
            h.player.teleport(Location(other, 85.5, 64.0, 0.5))
            h.renderer.pulse(h.player, h.city)
            h.paper.performTicks(2)
            h.remaining.size shouldBe 0
            val closing = CompletableFuture<Void>()
            every { h.displays.closeAsync() } returns closing
            h.renderer.close()
            h.renderer.drained().isDone shouldBe false
            closing.complete(null)
            h.renderer.drained().isDone shouldBe true
            h.renderer.close()
            verify(exactly = 1) { h.displays.closeAsync() }
        }
    }

    test("export uses the same near-edge and corner geometry as the runtime") {
        val city = boundaryCity(UUID(0, 1), UUID(0, 2))
        val scenes = listOf(
            Triple("edge", listOf(85.5, 65.62, 0.5), listOf(97.0, 65.2, 0.5)),
            Triple("corner", listOf(88.5, 65.62, 88.5), listOf(97.0, 65.2, 97.0)),
        ).map { (name, eye, target) -> mapOf(
            "id" to name, "eye" to eye, "target" to target, "groundY" to 64,
            "cues" to civsBoundaryGeometry(city, eye[0], eye[1] - 1.62, eye[2]).map { it.cuboid },
        ) }
        System.getenv("CIVS_BOUNDARY_PREVIEW_OUT")?.let { destination ->
            val out = Path.of(destination)
            Files.createDirectories(out)
            val source = Path.of("src/main/kotlin/ru/arc/civs/CivsBoundaryRenderer.kt")
            val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)).joinToString("") { "%02x".format(it) }
            Files.writeString(out.resolve("scene.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                "evidence" to "Compiled canonical boundary geometry; explicit flat terrain fixture, no native-client rendering.",
                "sourceSha256" to mapOf(source.toString() to hash), "scenes" to scenes)))
        }
    }
})

private fun boundaryScenario(budget: Int = 512, action: (BoundaryHarness) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper ->
        val h = BoundaryHarness(paper, budget)
        try { action(h) } finally { h.renderer.close(); h.work.close(); h.tasks.close() }
    }
}

private class BoundaryHarness(val paper: MockBukkitTestRuntime, budget: Int) {
    val plugin = paper.createSimplePlugin("CivsBoundaryTest")
    val player = paper.addPlayer("Mayor").apply { teleport(Location(world, 85.5, 64.0, 0.5)) }
    val city = boundaryCity(player.world.uid, player.uniqueId)
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val work = BudgetedTaskQueue(tasks, maximumSteps = budget, nanoTime = { 0 })
    val displays = mockk<PaperPacketDisplays>(relaxed = true)
    val handles = mutableListOf<PacketBlockDisplay>()
    val remaining = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PacketBlockDisplay, Boolean>())
    val renderer: CivsBoundaryRenderer

    init {
        every { displays.spawnBlock(any(), any()) } answers {
            mockk<PacketBlockDisplay>(relaxed = true).also { handle ->
                handles += handle
                remaining += handle
                every { handle.remove() } answers { remaining.remove(handle); Unit }
            }
        }
        every { displays.closeAsync() } answers {
            remaining.toList().forEach { it.remove() }
            CompletableFuture.completedFuture(null)
        }
        renderer = CivsBoundaryRenderer(plugin, tasks, displays, work)
    }
}

private fun boundaryCity(worldId: UUID, owner: UUID): Settlement = Settlement(UUID(1, 1), owner, "Boundary", worldId,
    CivPosition(0, 64, 0), 96, "fixture", Stock(), emptyList(), emptyList())
