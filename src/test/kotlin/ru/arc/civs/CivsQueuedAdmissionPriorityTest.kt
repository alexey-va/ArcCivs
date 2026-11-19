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
            h.activate = { city, _ ->
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
            h.activate = { _, _ -> h.pending }
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
            h.activate = { _, _ ->
                if (synchronous) throw CivsRejection("lands-denied")
                CompletableFuture.failedFuture(CivsRejection("lands-denied"))
            }
            h.runtime.pulseQueue(); h.drain()
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe "lands-denied"
            h.activate = { _, _ -> h.pending }
            h.time += 5_000
            h.runtime.pulseQueue()
            h.activations shouldBe listOf(h.city.id, h.city.id)
        }
    }

    test("an unloaded site lets the next loaded site start and regains its position when loaded") {
        QueueAdmissionHarness().use { h ->
            val next = h.queueSite(36)
            val tail = h.queueSite(52)
            val chunk = (h.head.entrance.x shr 4) to (h.head.entrance.z shr 4)
            h.unloaded += chunk
            h.activate = h::accept
            h.runtime.pulseQueue()
            h.drain()
            h.attempts shouldBe listOf(h.city.id to next.id)
            h.city.constructionQueue shouldBe listOf(h.head.id, tail.id)
            h.city = completeQueuedTestSite(h.city)
            h.unloaded -= chunk
            h.time += 5_000
            h.runtime.pulseQueue()
            h.drain()
            h.attempts shouldBe listOf(h.city.id to next.id, h.city.id to h.head.id)
            h.city.constructionQueue shouldBe listOf(tail.id)
        }
    }

    test("a denied head lets the next site and another city start before its retry") {
        QueueAdmissionHarness().use { h ->
            val next = h.queueSite(36)
            val tail = h.queueSite(52)
            val secondHead = h.second.constructionQueue!!.single()
            h.activate = { current, building ->
                when {
                    building.id == h.head.id -> CompletableFuture.failedFuture(CivsRejection("lands-denied"))
                    current.id == h.second.id -> CompletableFuture.completedFuture(Unit)
                    else -> h.accept(current, building)
                }
            }
            h.runtime.pulseQueue()
            h.drain()
            h.runtime.queueFailure(h.city.id, h.head.id) shouldBe "lands-denied"
            h.secondPresent = true
            h.time += 5_000
            repeat(2) { h.runtime.pulseQueue(); h.drain() }
            h.attempts shouldBe listOf(h.city.id to h.head.id, h.second.id to secondHead, h.city.id to next.id)
            h.city.constructionQueue shouldBe listOf(h.head.id, tail.id)
            h.city = completeQueuedTestSite(h.city)
            h.time += 5_000
            h.runtime.pulseQueue()
            h.drain()
            h.attempts.last() shouldBe (h.city.id to tail.id)
            h.city.constructionQueue shouldBe listOf(h.head.id)
        }
    }

    test("a material wait yields only after durable acknowledgement and resumes without activating its site again") {
        QueueAdmissionHarness().use { h ->
            val next = h.queueSite(36)
            val target = h.head.copy(phase = BuildingPhase.CONSTRUCTING)
            val awaiting = mapOf("STONE" to 1L)
            val order = CivConstruction(UUID(176, 100), target.id, 1, 3, 1, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(3, 16), materials = awaiting,
                payment = CivConstructionPayment(awaiting = awaiting), workCredit = 1, blocksRequired = 3, blocksPlaced = 1)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map { if (it == h.head) target else it },
                construction = order, constructionQueue = listOf(next.id)))
            val commit = CompletableFuture<Unit>()
            val saves = mutableListOf<Settlement>()
            h.persist = { updated ->
                saves += updated
                if (saves.size == 1) commit else CompletableFuture.completedFuture(Unit)
            }
            h.activate = h::accept
            h.runtime.pulseQueue()
            h.time += 5_000
            h.runtime.pulseQueue()
            h.runtime.pulse()
            h.drain()
            saves.single().deferredConstruction shouldBe listOf(order)
            h.city.construction shouldBe order
            h.attempts shouldBe emptyList()
            commit.complete(Unit)
            h.drain()
            h.city.construction?.buildingId shouldBe next.id
            h.city.constructionQueue shouldBe listOf(target.id)
            h.runtime.queueFailure(h.city.id, target.id) shouldBe "materials-required"
            h.city = completeQueuedTestSite(h.city)
            h.time += 29_999
            h.runtime.pulseQueue()
            h.drain()
            h.city.construction shouldBe null
            h.time++
            h.runtime.pulseQueue()
            h.drain()
            h.city.construction shouldBe order
            h.city.deferredConstruction shouldBe null
            h.attempts shouldBe listOf(h.city.id to next.id)
        }
    }
})

private class QueueAdmissionHarness : AutoCloseable {
    val head = CivBuilding(UUID(176, 80), BuildingType.HOUSE, 1, CivPosition(20, 64, 0), 0, "fixture/queue",
        CivBox(CivPosition(19, 64, -4), CivPosition(21, 70, -1)), CivPosition(20, 65, 0), BuildingPhase.SITE,
        control = CivPosition(20, 64, -1))
    var city = staffedQueueTestCity(settlementPlanCity()).let { it.copy(buildings = it.buildings + head, constructionQueue = listOf(head.id)) }
    val second = head.copy(id = UUID(176, 81)).let { secondHead ->
        city.copy(id = UUID(176, 82), worldId = UUID(176, 83), buildings = city.buildings - head + secondHead,
            constructionQueue = listOf(secondHead.id))
    }
    var secondPresent = false
    var closed = false
    val busy = mutableSetOf<UUID>()
    var time = 10_000L
    val unloaded = mutableSetOf<Pair<Int, Int>>()
    val attempts = mutableListOf<Pair<UUID, UUID>>()
    val activations get() = attempts.map { it.first }
    val pending = CompletableFuture<Unit>()
    var activate: (Settlement, CivBuilding) -> CompletableFuture<Unit> = { _, _ -> pending }
    var persist: (Settlement) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) }
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
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { (firstArg<Int>() to secondArg<Int>()) !in unloaded }
        runtime = CivsConstructionRuntime(plugin, tasks, mockk(), mockk(), mockk(), Executor(Runnable::run),
            mockk(), mockk(), mockk(), mockk(), { if (secondPresent) mapOf(city.id to city, second.id to second) else mapOf(city.id to city) },
            busy, emptySet(), { true }, { closed }, { true }, { _, action -> action() },
            { id, update ->
                check(id == city.id)
                val updated = update(city)
                persist(updated).thenApply { updated.also { city = it } }
            }, ::startActivation,
            { _, _, _ -> error("Admission priority must not pay a batch") }, {}, { time })
    }
    fun queueSite(x: Int): CivBuilding {
        val site = head.copy(id = UUID(176, x.toLong()), anchor = head.anchor.copy(x = x),
            bounds = CivBox(head.bounds.min.copy(x = x - 1), head.bounds.max.copy(x = x + 1)), entrance = head.entrance.copy(x = x),
            control = head.control!!.copy(x = x))
        city = CivsRules.validate(city.copy(buildings = city.buildings + site, constructionQueue = city.constructionQueue.orEmpty() + site.id))
        return site
    }
    fun accept(current: Settlement, building: CivBuilding): CompletableFuture<Unit> {
        city = admitQueuedTestSite(current, building)
        return CompletableFuture.completedFuture(Unit)
    }
    private fun startActivation(current: Settlement, building: CivBuilding): CompletableFuture<Unit> {
        attempts += current.id to building.id
        busy += current.id
        return try { activate(current, building).whenComplete { _, _ -> busy -= current.id } }
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

internal fun staffedQueueTestCity(city: Settlement): Settlement {
    val position = CivPosition(-16, 64, 0)
    val hut = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1, position, 0, "fixture/hut",
        CivBox(position, position.copy(y = 66)), position.copy(z = 1), BuildingPhase.ACTIVE, control = position)
    val builder = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1)
    return city.copy(buildings = city.buildings + hut, workers = city.workers + builder, inventory = CivInventory(CivStorageMode.PHYSICAL))
}

/** A single free row lets the scheduler tests finish the replacement through the construction rules. */
internal fun admitQueuedTestSite(city: Settlement, building: CivBuilding): Settlement {
    check(CivsAutoStorageMigration.ready(city) && building.control != null && CivsRules.constructionWorkers(city, building.id).isNotEmpty())
    val order = CivConstruction(UUID.randomUUID(), building.id, 1, 1, 0, ConstructionPhase.CONSTRUCTING,
        Stock(), batches = CivConstructionBatches(1, 16), materials = emptyMap(), payment = CivConstructionPayment())
    return CivsConstructionRules.reserveBuild(city, city.owner, building, order, selectedQueuedBuilding = building.id)
}

internal fun completeQueuedTestSite(city: Settlement): Settlement {
    val order = requireNotNull(city.construction)
    val ready = city.copy(construction = order.copy(phase = ConstructionPhase.READY_TO_PLACE))
    val paid = CivsRules.payBatch(ready, city.owner, order.id, 0, emptyMap())
    return CivsRules.completeBatch(paid, city.owner, order.id)
}
