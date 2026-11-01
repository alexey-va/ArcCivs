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
    test("queue admission is visible before async activation and stays held through delayed reads and city busy") {
        QueueAdmissionHarness().use { h ->
            h.runtime.queuedAdmissionPending() shouldBe false
            h.runtime.pulseQueue()
            h.visibleInsideActivation shouldBe true
            h.runtime.queuedAdmissionPending() shouldBe true
            (h.city.id in h.busy) shouldBe true
            h.time += 5_001
            h.runtime.pulseQueue()
            h.activations shouldBe 1
            h.pending.complete(Unit)
            h.drain()
            h.runtime.queuedAdmissionPending() shouldBe false
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe null
        }
    }

    test("world busy preserves the queue turn through its existing five second retry then success clears it") {
        QueueAdmissionHarness().use { h ->
            h.activate = { CompletableFuture.failedFuture(CivsRejection("world-busy")) }
            h.runtime.pulseQueue(); h.drain()
            h.runtime.queuedAdmissionPending() shouldBe true
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe "world-busy"
            h.time += 4_999
            h.runtime.pulseQueue()
            h.activations shouldBe 1
            h.runtime.queuedAdmissionPending() shouldBe true
            h.activate = { h.pending }
            h.time += 2
            h.runtime.pulseQueue()
            h.activations shouldBe 2
            h.runtime.queuedAdmissionPending() shouldBe true
            h.pending.complete(Unit); h.drain()
            h.runtime.queuedAdmissionPending() shouldBe false
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
            h.visibleInsideActivation shouldBe true
            h.runtime.queuedAdmissionPending() shouldBe false
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe "lands-denied"
        }
    }

    for (inFlight in listOf(false, true))
    test("paused unloaded unhealthy removed and replaced queue heads do not hold other development inFlight=$inFlight") {
        for (state in listOf("paused", "unloaded", "unhealthy", "removed", "empty", "replaced")) {
            QueueAdmissionHarness().use { h ->
                if (!inFlight) h.activate = { CompletableFuture.failedFuture(CivsRejection("world-busy")) }
                h.runtime.pulseQueue(); h.drain()
                h.runtime.queuedAdmissionPending() shouldBe true
                when (state) {
                    "paused" -> h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(paused = true))
                    "unloaded" -> h.loaded = false
                    "unhealthy" -> h.unhealthy += h.city.id
                    "removed" -> h.present = false
                    "empty" -> h.city = h.city.copy(constructionQueue = emptyList())
                    "replaced" -> h.head.copy(id = UUID(176, 99)).let { replacement ->
                        h.city = h.city.copy(buildings = h.city.buildings + replacement,
                            constructionQueue = listOf(replacement.id))
                    }
                }
                h.runtime.queuedAdmissionPending() shouldBe false
            }
        }
    }
})

private class QueueAdmissionHarness : AutoCloseable {
    val head = CivBuilding(UUID(176, 80), BuildingType.HOUSE, 1, CivPosition(20, 64, 0), 0, "fixture/queue",
        CivBox(CivPosition(19, 64, -4), CivPosition(21, 70, -1)), CivPosition(20, 65, 0), BuildingPhase.SITE)
    var city = settlementPlanCity().let { it.copy(buildings = it.buildings + head, constructionQueue = listOf(head.id)) }
    var present = true
    var loaded = true
    var closed = false
    val busy = mutableSetOf<UUID>()
    val unhealthy = mutableSetOf<UUID>()
    var time = 10_000L
    var activations = 0
    var visibleInsideActivation = false
    val pending = CompletableFuture<Unit>()
    var activate: () -> CompletableFuture<Unit> = { pending }
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler)
    private val plugin = mockk<Plugin>()
    private val server = mockk<Server>()
    private val world = mockk<World>()
    val runtime: CivsConstructionRuntime
    init {
        every { plugin.server } returns server
        every { plugin.logger } returns Logger.getLogger("CivsQueuedAdmissionPriorityTest")
        every { server.getWorld(city.worldId) } returns world
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        runtime = CivsConstructionRuntime(plugin, tasks, mockk(), mockk(), mockk(), Executor(Runnable::run),
            mockk(), mockk(), mockk(), mockk(), { if (present) mapOf(city.id to city) else emptyMap() },
            busy, unhealthy, { true }, { closed }, { true }, { true }, { _, action -> action() },
            { _, _ -> error("Admission priority must not mutate a city") },
            { current, _ -> activate(current) },
            { _, _, _ -> error("Admission priority must not pay a batch") }, {}, { time })
    }
    private fun activate(current: Settlement): CompletableFuture<Unit> {
        activations++
        visibleInsideActivation = runtime.queuedAdmissionPending()
        busy += current.id
        return try { activate().whenComplete { _, _ -> busy -= current.id } }
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
