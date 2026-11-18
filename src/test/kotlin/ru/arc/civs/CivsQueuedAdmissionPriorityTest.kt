package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger

class CivsQueuedAdmissionPriorityTest : FunSpec({
    test("a pending activation retains its city admission through delayed reads") {
        QueueAdmissionHarness().use { h ->
            h.runtime.pulseQueue()
            h.activations shouldBe listOf(h.city.id)
            (h.city.id in h.busy) shouldBe true
            // Activation can release the city while a control relocation is pending.
            h.busy.clear()
            h.time += 5_001
            h.runtime.pulseQueue()
            h.activations shouldBe listOf(h.city.id)
            h.pending.complete(Unit)
            h.drain()
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe null
        }
    }

    test("world busy delays the failed city and lets another city's queue head start") {
        QueueAdmissionHarness().use { h ->
            h.activate = { city ->
                if (city.id == h.city.id) CompletableFuture.failedFuture(CivsRejection("world-busy"))
                else CompletableFuture.completedFuture(Unit)
            }
            h.runtime.pulseQueue(); h.drain()
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe "world-busy"
            h.secondPresent = true
            h.time += 1_000
            h.runtime.pulseQueue(); h.drain()
            h.activations shouldBe listOf(h.city.id, h.second.id)
            h.time += 3_999
            h.runtime.pulseQueue()
            h.activations shouldBe listOf(h.city.id, h.second.id)
            h.activate = { h.pending }
            h.time++
            h.runtime.pulseQueue()
            h.activations shouldBe listOf(h.city.id, h.second.id, h.city.id)
            h.pending.complete(Unit); h.drain()
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe null
        }
    }

    for (synchronous in listOf(false, true))
    test("a permanent activation ${if (synchronous) "throw" else "failure"} clears the admission hold") {
        QueueAdmissionHarness().use { h ->
            h.activate = {
                if (synchronous) throw CivsRejection("lands-denied")
                CompletableFuture.failedFuture(CivsRejection("lands-denied"))
            }
            h.runtime.pulseQueue(); h.drain()
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe "lands-denied"
            h.activate = { h.pending }
            h.time += 5_000
            h.runtime.pulseQueue()
            h.activations shouldBe listOf(h.city.id, h.city.id)
        }
    }
})

private class QueueAdmissionHarness : AutoCloseable {
    val head = CivBuilding(UUID(176, 80), BuildingType.HOUSE, 1, CivPosition(20, 64, 0), 0, "fixture/queue",
        CivBox(CivPosition(19, 64, -4), CivPosition(21, 70, -1)), CivPosition(20, 65, 0), BuildingPhase.SITE)
    val city = settlementPlanCity().let { it.copy(buildings = it.buildings + head, constructionQueue = listOf(head.id)) }
    val second = head.copy(id = UUID(176, 81)).let { secondHead ->
        city.copy(id = UUID(176, 82), worldId = UUID(176, 83), buildings = city.buildings - head + secondHead,
            constructionQueue = listOf(secondHead.id))
    }
    var secondPresent = false
    var closed = false
    val busy = mutableSetOf<UUID>()
    var time = 10_000L
    val activations = mutableListOf<UUID>()
    val pending = CompletableFuture<Unit>()
    var activate: (Settlement) -> CompletableFuture<Unit> = { pending }
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    private val plugin = mockk<Plugin>()
    private val server = mockk<Server>()
    private val world = mockk<World>()
    val runtime: CivsConstructionRuntime
    init {
        every { plugin.server } returns server
        every { plugin.logger } returns Logger.getLogger("CivsQueuedAdmissionPriorityTest")
        every { server.getWorld(any<UUID>()) } returns world
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        runtime = CivsConstructionRuntime(plugin, tasks, mockk(), mockk(), mockk(), Executor(Runnable::run),
            mockk(), mockk(), mockk(), mockk(), { if (secondPresent) mapOf(city.id to city, second.id to second) else mapOf(city.id to city) },
            busy, emptySet(), { true }, { closed }, { true }, { true }, { _, action -> action() },
            { _, _ -> error("Admission priority must not mutate a city") },
            { current, _ -> startActivation(current) },
            { _, _, _ -> error("Admission priority must not pay a batch") }, {}, { time })
    }
    private fun startActivation(current: Settlement): CompletableFuture<Unit> {
        activations += current.id
        busy += current.id
        return try { activate(current).whenComplete { _, _ -> busy -= current.id } }
        catch (failure: Throwable) { busy -= current.id; throw failure }
    }
    fun drain() {
        var rounds = 0
        while (scheduler.pendingCount() > 0) {
            check(rounds++ < 20) { "Queue admission callbacks did not drain" }
            scheduler.executeAll()
        }
    }
    override fun close() { closed = true; runtime.close(); tasks.close() }
}
