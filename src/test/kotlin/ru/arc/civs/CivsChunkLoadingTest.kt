package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsChunkLoadingTest : FunSpec({
    test("handoff waits for initial and expired permission hydration but terminates on an observed denial") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            val initial = CompletableFuture<Boolean>()
            h.permissionRead = { initial }
            h.cities = listOf(city)
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.runtime.handoffReady(city) shouldBe false
            h.pulse()
            h.runtime.handoffReady(city) shouldBe false
            initial.complete(true)
            repeat(25) { h.pulse() }
            h.runtime.handoffReady(city) shouldBe true
            val refresh = CompletableFuture<Boolean>()
            h.permissionRead = { refresh }
            h.now += 30_000
            h.pulse()
            h.runtime.active(city.id) shouldBe false
            h.runtime.handoffReady(city) shouldBe false
            refresh.complete(false)
            h.pulse()
            shouldThrow<CivsRejection> { h.runtime.handoffReady(city) }.reason shouldBe "not-authorized"
            h.runtime.retainedChunks shouldBe 0
        }
    }

    test("handoff reports observed provider failure instead of treating an ungranted initial request as denial") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            val request = CompletableFuture<Boolean>()
            h.permissionRead = { request }
            h.cities = listOf(city)
            h.pulse()
            h.runtime.handoffReady(city) shouldBe false
            request.completeExceptionally(IllegalStateException("provider unavailable"))
            h.pulse()
            shouldThrow<CivsRejection> { h.runtime.handoffReady(city) }.reason shouldBe "chunk-permission-unavailable"
            h.added shouldBe emptyList()
        }
    }

    for (failure in listOf("async-null", "async-registry", "synchronous-registry"))
    test("handoff terminates on an observed $failure chunk failure") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            h.cities = listOf(city)
            if (failure == "synchronous-registry") {
                h.loaded = true
                h.failAcquire = CivsChunkArea.of(city).keys().first()
            } else h.perChunkLoads = true
            h.pulse(); h.pulse()
            if (failure == "async-null") h.loads.values.first().complete(null)
            else if (failure == "async-registry") {
                val key = h.loads.keys.first()
                h.failAcquire = key
                h.completeLoad(key, evictUnticketed = true)
            }
            h.pulse()
            shouldThrow<CivsRejection> { h.runtime.handoffReady(city) }.reason shouldBe "chunk-unavailable"
            h.runtime.active(city.id) shouldBe false
        }
    }

    for (failure in listOf("capacity", "close", "failed", "world-unload"))
    test("handoff reports known $failure instead of waiting for native readiness forever") {
        chunkRuntime(limit = 16) { h ->
            val city = chunkCity(h.world.uid, radius = if (failure == "capacity") 96 else 1)
            h.cities = listOf(city)
            if (failure == "failed") h.loaded = true
            h.pulse(); h.pulse()
            if (failure == "close") h.runtime.close()
            else if (failure == "failed") {
                h.failRelease = h.added.first()
                h.runtime.reset()
            }
            else if (failure == "world-unload") {
                val event = mockk<WorldUnloadEvent>()
                every { event.world } returns h.world
                h.runtime.worldUnloading(event)
            }
            try {
                shouldThrow<CivsRejection> { h.runtime.handoffReady(city) }.reason shouldBe when (failure) {
                    "capacity" -> "chunk-capacity"
                    "close", "failed" -> "chunk-loading-failed"
                    else -> "world-unavailable"
                }
            } finally { h.failRelease = null }
        }
    }

    test("async completion pins unviewed chunks before returning and reaches ordinary ticking readiness") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            h.cities = listOf(city)
            h.perChunkLoads = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            for (pulse in 1..40) {
                val before = h.added.size
                h.pulse()
                h.completeLoads(evictUnticketed = true)
                (h.added.size - before <= 2) shouldBe true
                (h.loads.values.count { !it.isDone } <= 2) shouldBe true
                if (h.runtime.active(city.id)) break
            }
            h.runtime.active(city.id) shouldBe true
            h.added.size shouldBe 16
            h.loads.size shouldBe 16
            h.cities = emptyList()
            h.pulse()
            h.runtime.retainedChunks shouldBe 0
            h.removed.groupingBy { it }.eachCount() shouldBe h.added.groupingBy { it }.eachCount()
        }
    }

    test("permanent acquisition waits for the late member of its two-load cohort") {
        chunkRuntime { h ->
            h.perChunkLoads = true
            h.recoveryChunks = listOf((0..3).mapTo(linkedSetOf()) { PaperChunkKey(h.world.uid, it, 20) })
            h.pulse()
            h.loads.size shouldBe 2
            h.completeLoad(h.loads.keys.first(), evictUnticketed = true)
            h.runtime.retainedChunks shouldBe 1
            h.pulse()
            h.loads.size shouldBe 2
            h.completeLoad(h.loads.keys.last(), evictUnticketed = true)
            h.runtime.retainedChunks shouldBe 2
            h.pulse()
            h.loads.size shouldBe 3
            h.recoveryChunks = emptyList()
            h.pulse()
            h.completeLoads(evictUnticketed = true)
            h.pulse()
            h.runtime.retainedChunks shouldBe 0
            h.added.size shouldBe 2
            h.removed.groupingBy { it }.eachCount() shouldBe h.added.groupingBy { it }.eachCount()
        }
    }

    for (stop in listOf("retire", "reset", "close", "world-unload"))
    test("$stop releases a callback-owned permanent ticket once before its reply and rejects a stale sibling") {
        chunkRuntime { h ->
            h.perChunkLoads = true
            h.recoveryChunks = listOf((0..1).mapTo(linkedSetOf()) { PaperChunkKey(h.world.uid, it, 20) })
            h.pulse()
            val keys = h.loads.keys.toList()
            h.completeLoad(keys.first(), evictUnticketed = true)
            h.runtime.retainedChunks shouldBe 1
            when (stop) {
                "retire" -> { h.recoveryChunks = emptyList(); h.pulse() }
                "reset" -> { h.runtime.reset(); h.recoveryChunks = emptyList() }
                "close" -> h.runtime.close()
                else -> {
                    val event = mockk<WorldUnloadEvent>()
                    every { event.world } returns h.world
                    h.runtime.worldUnloading(event)
                }
            }
            h.completeLoad(keys.last(), evictUnticketed = true)
            h.pulse()
            h.runtime.retainedChunks shouldBe 0
            h.added shouldBe listOf(keys.first())
            h.removed shouldBe h.added
        }
    }

    test("an off-thread injected permanent load completion cannot acquire a ticket") {
        chunkRuntime { h ->
            h.perChunkLoads = true
            val key = PaperChunkKey(h.world.uid, 2, 20)
            h.recoveryChunks = listOf(setOf(key))
            h.pulse()
            h.loadedKeys += key
            CompletableFuture.runAsync { h.loads.getValue(key).complete(h.chunk) }.join()
            h.pulse()
            h.added shouldBe emptyList()
            h.runtime.retainedChunks shouldBe 0
            h.runtime.debug(null)["pendingChunks"] shouldBe 0
        }
    }

    test("a failed callback acquisition preserves its sibling until demand retires without retrying a grant") {
        chunkRuntime { h ->
            h.perChunkLoads = true
            val keys = (0..1).mapTo(linkedSetOf()) { PaperChunkKey(h.world.uid, it, 20) }
            h.recoveryChunks = listOf(keys)
            h.pulse()
            h.failAcquire = keys.last()
            h.completeLoads(evictUnticketed = true)
            h.runtime.retainedChunks shouldBe 1
            h.added shouldBe listOf(keys.first())
            h.pulse()
            h.recoveryChunks = emptyList()
            h.pulse()
            h.runtime.retainedChunks shouldBe 0
            h.removed shouldBe h.added
        }
    }

    test("shared temporary tickets keep the whole town while the ordinary owner acquires every lease before release") {
        chunkRuntime(limit = 32) { h ->
            h.perChunkLoads = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            val city = chunkCity(h.world.uid, radius = 1)
            val keys = CivsChunkArea.of(city).keys()
            h.loadedKeys += keys
            val temporary = keys.map { (h.registry.acquire(it) as PaperChunkTicketAcquireResult.Acquired).lease }
            h.registry.activeLeaseCount shouldBe 16
            h.cities = listOf(city)
            h.runtime.active(city.id) shouldBe false
            repeat(25) {
                h.pulse()
                keys.all { h.world.isChunkLoaded(it.x, it.z) } shouldBe true
                h.removed shouldBe emptyList()
            }
            h.runtime.active(city.id) shouldBe true
            h.registry.activeLeaseCount shouldBe 32
            temporary.forEach { it.close() }
            h.registry.activeLeaseCount shouldBe 16
            h.runtime.active(city.id) shouldBe true
            h.removed shouldBe emptyList()
            h.cities = emptyList()
            h.pulse()
            h.registry.activeLeaseCount shouldBe 0
            h.removed.toSet() shouldBe keys
            h.removed.size shouldBe 16
        }
    }

    test("city switch releases tickets while permission remains granted and can admit again") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.cities = listOf(city)
            repeat(30) { h.pulse() }
            h.runtime.active(city.id) shouldBe true
            h.cities = listOf(city.copy(settings = CivSettlementSettings(chunkLoading = false)))
            h.pulse()
            h.runtime.active(city.id) shouldBe false
            h.runtime.retainedChunks shouldBe 0
            h.cities = listOf(city)
            repeat(30) { h.pulse() }
            h.runtime.active(city.id) shouldBe true
        }
    }
    test("full territory and route margin include negative boundaries and all town hall levels") {
        val city = chunkCity(UUID.randomUUID())
        for ((radius, chunks) in listOf(96 to 225L, 128 to 361L, 160 to 529L)) {
            val area = CivsChunkArea.of(city.copy(radius = radius))
            area.size shouldBe chunks
            area.keys().size.toLong() shouldBe chunks
            val negative = CivsChunkArea.of(city.copy(center = CivPosition(-1, 64, -17), radius = radius))
            negative.size shouldBe chunks
            negative.minX shouldBe ((-1 - radius) shr 4) - 1
            negative.maxZ shouldBe ((-17 + radius) shr 4) + 1
        }
        (CivsChunkArea.of(city.copy(radius = Int.MAX_VALUE)).size > 8192) shouldBe true
    }

    test("whole-city admission waits for every ticking ticket and uses no more than two acquisitions per pulse") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            h.cities = listOf(city)
            h.loaded = true
            repeat(30) {
                val before = h.added.size
                h.pulse()
                (h.added.size - before <= 2) shouldBe true
                if (h.added.size < 16) h.runtime.active(city.id) shouldBe false
            }
            h.added.size shouldBe 16
            h.runtime.active(city.id) shouldBe false
            val waitingDebug = h.runtime.debug(city.id)
            waitingDebug["requiredLoadLevel"] shouldBe Chunk.LoadLevel.ENTITY_TICKING
            val observed = ((waitingDebug["chunks"] as Map<*, *>)["entries"] as List<*>).first() as Map<*, *>
            observed["loadLevelLastPulse"] shouldBe Chunk.LoadLevel.TICKING
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.pulse()
            h.runtime.active(city.id) shouldBe true
            val activeDebug = h.runtime.debug(city.id)
            val ticking = ((activeDebug["chunks"] as Map<*, *>)["entries"] as List<*>).first() as Map<*, *>
            ticking["loadLevelLastPulse"] shouldBe Chunk.LoadLevel.ENTITY_TICKING
            h.allowed = false
            h.now += 15_000
            h.pulse(); h.pulse()
            h.runtime.active(city.id) shouldBe false
            h.removed.toSet() shouldBe h.added.toSet()
            h.runtime.retainedChunks shouldBe 0
        }
    }

    test("global cap admits no partial second city and releasing one makes room") {
        chunkRuntime(limit = 16) { h ->
            val first = chunkCity(h.world.uid, radius = 1)
            val second = chunkCity(h.world.uid, radius = 1).copy(center = CivPosition(160, 64, 0))
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.cities = listOf(first, second)
            repeat(25) { h.pulse() }
            h.runtime.active(first.id) shouldBe true
            h.runtime.active(second.id) shouldBe false
            h.added.size shouldBe 16
            h.cities = listOf(second)
            repeat(25) { h.pulse() }
            h.runtime.active(first.id) shouldBe false
            h.runtime.active(second.id) shouldBe true
            h.runtime.retainedChunks shouldBe 16
            h.removed.size shouldBe 16
        }
    }

    test("async loads stay bounded and revoked or pre-reload completions acquire no stale tickets") {
        chunkRuntime { h ->
            h.cities = listOf(chunkCity(h.world.uid, radius = 1))
            repeat(15) { h.pulse() }
            h.loads.size shouldBe 2
            h.added shouldBe emptyList()
            h.cities = emptyList()
            h.pulse()
            h.loaded = true
            h.loads.values.forEach { it.complete(h.chunk) }
            h.pulse()
            h.added shouldBe emptyList()
            h.loaded = false
            h.cities = listOf(chunkCity(h.world.uid, radius = 1))
            repeat(4) { h.pulse() }
            val old = h.loads.values.filterNot { it.isDone }
            old.size shouldBe 2
            h.runtime.reset()
            h.loaded = true
            old.forEach { it.complete(h.chunk) }
            h.runtime.pulse(emptyList())
            h.added shouldBe emptyList()
            h.runtime.close()
            h.runtime.pulse(h.cities)
            h.added shouldBe emptyList()
        }
    }

    test("overlapping city margins share native tickets and close releases only once") {
        chunkRuntime(limit = 16) { h ->
            val first = chunkCity(h.world.uid, radius = 1)
            val second = first.copy(id = UUID.randomUUID(), owner = UUID.randomUUID())
            h.cities = listOf(first, second)
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            repeat(25) { h.pulse() }
            h.runtime.active(first.id) shouldBe true
            h.runtime.active(second.id) shouldBe true
            h.added.size shouldBe 16
            h.cities = listOf(second)
            h.pulse()
            h.removed shouldBe emptyList()
            h.runtime.close()
            h.removed.size shouldBe 16
        }
    }

    test("global debug retains a failed ticket release after its city retires") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            h.cities = listOf(city)
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            repeat(25) { h.pulse() }
            h.runtime.active(city.id) shouldBe true
            h.added.size shouldBe 16
            val failed = h.added.first()
            h.failRelease = failed
            try {
                h.cities = emptyList()
                h.pulse()
                h.runtime.active(city.id) shouldBe false
                h.runtime.retainedChunks shouldBe 1
                val state = h.runtime.debug(null)
                state["total"] shouldBe 0
                state["releaseFailures"] shouldBe 1
                state["leasedChunks"] shouldBe 0
                val chunks = state["chunks"] as Map<*, *>
                chunks["total"] shouldBe 1
                chunks["truncated"] shouldBe false
                val entry = (chunks["entries"] as List<*>).single() as Map<*, *>
                entry["world"] shouldBe failed.worldId.toString()
                entry["x"] shouldBe failed.x
                entry["z"] shouldBe failed.z
                entry["releaseFailed"] shouldBe true
                entry["leased"] shouldBe false
                h.removed.count { it == failed } shouldBe 1
            } finally { h.failRelease = null }
        }
    }

    test("missing chunks never activate a city and configuration preserves valid limits") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            h.cities = listOf(city)
            h.pulse(); h.pulse()
            h.loads.values.toList().forEach { it.complete(null) }
            h.pulse()
            h.runtime.active(city.id) shouldBe false
            h.added shouldBe emptyList()
        }
        val config = YamlConfiguration()
        CivsSettings.chunkLoadingMaximum(config) shouldBe 2048
        config.set("chunk-loading.maximum-chunks", 529)
        CivsSettings.chunkLoadingMaximum(config) shouldBe 529
        val warnings = mutableListOf<String>()
        for (invalid in listOf(0, 8193, "2048")) {
            config.set("chunk-loading.maximum-chunks", invalid)
            CivsSettings.chunkLoadingMaximum(config, warnings::add) shouldBe 2048
        }
        warnings.size shouldBe 3
    }

    test("a cold receipt retains only its footprint and retirement releases it without city activation") {
        chunkRuntime { h ->
            val city = chunkCity(h.world.uid)
            val keys = setOf(PaperChunkKey(h.world.uid, -119, -173), PaperChunkKey(h.world.uid, -116, -172))
            h.recoveryChunks = listOf(keys)
            h.pulse()
            h.loads.keys shouldBe keys
            h.added shouldBe emptyList()
            h.runtime.active(city.id) shouldBe false
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.loads.values.forEach { it.complete(h.chunk) }
            h.pulse()
            h.added.toSet() shouldBe keys
            h.runtime.retainedChunks shouldBe 2
            h.runtime.active(city.id) shouldBe false
            h.runtime.debug(null)["total"] shouldBe 0
            h.recoveryChunks = emptyList()
            h.pulse()
            h.runtime.retainedChunks shouldBe 0
            h.removed.toSet() shouldBe keys
        }
    }

    test("receipt bundles share the global cap and two loads and acquisitions per pulse") {
        chunkRuntime(limit = 6) { h ->
            val first = (0..4).mapTo(linkedSetOf()) { PaperChunkKey(h.world.uid, it, 20) }
            val second = (10..11).mapTo(linkedSetOf()) { PaperChunkKey(h.world.uid, it, 20) }
            h.recoveryChunks = listOf(first, second)
            repeat(10) { h.pulse() }
            h.loads.size shouldBe 2
            h.runtime.debug(null)["wantedChunks"] shouldBe 5 // No partial admission of the second receipt.
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.loads.values.forEach { it.complete(h.chunk) }
            repeat(4) {
                val before = h.added.size
                h.pulse()
                (h.added.size - before <= 2) shouldBe true
                (h.runtime.retainedChunks <= 6) shouldBe true
            }
            h.added.toSet() shouldBe first
            h.recoveryChunks = listOf(second)
            repeat(4) { h.pulse() }
            h.runtime.retainedChunks shouldBe 2
            h.added.takeLast(2).toSet() shouldBe second
            h.removed.toSet() shouldBe first
        }
    }

    test("ordinary and receipt demand share one lease until the last owner releases it") {
        chunkRuntime(limit = 16) { h ->
            val city = chunkCity(h.world.uid, radius = 1)
            val key = CivsChunkArea.of(city).keys().first()
            h.cities = listOf(city)
            h.recoveryChunks = listOf(setOf(key))
            h.loaded = true
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            repeat(25) { h.pulse() }
            h.runtime.active(city.id) shouldBe true
            h.added.size shouldBe 16
            h.cities = emptyList()
            h.pulse()
            h.runtime.active(city.id) shouldBe false
            h.runtime.retainedChunks shouldBe 1
            h.recoveryChunks = emptyList()
            h.pulse()
            h.runtime.retainedChunks shouldBe 0
            h.removed.count { it == key } shouldBe 1
            h.removed.size shouldBe 16
        }
    }

    test("receipt demand retries unavailable and unloaded chunks but stale completions acquire no lease") {
        chunkRuntime { h ->
            val key = PaperChunkKey(h.world.uid, 2, 3)
            h.recoveryChunks = listOf(setOf(key))
            h.pulse()
            h.loads.getValue(key).complete(null)
            h.pulse()
            val missing = h.loads.getValue(key)
            h.now += 30_000
            h.pulse()
            (h.loads.getValue(key) !== missing) shouldBe true
            h.loaded = true
            h.loads.getValue(key).complete(h.chunk)
            h.pulse()
            h.runtime.retainedChunks shouldBe 1
            val unload = mockk<ChunkUnloadEvent>()
            every { unload.world } returns h.world
            every { unload.chunk } returns h.chunk
            every { h.chunk.x } returns key.x
            every { h.chunk.z } returns key.z
            h.loaded = false
            h.runtime.chunkUnloading(unload)
            h.pulse()
            val waiting = h.loads.getValue(key)
            h.runtime.retainedChunks shouldBe 0
            h.recoveryChunks = emptyList()
            h.pulse() // Withdraw accepted demand before the native callback handoff.
            h.loaded = true
            waiting.complete(h.chunk)
            h.pulse()
            h.added shouldBe listOf(key)
            h.runtime.retainedChunks shouldBe 0
        }
    }
})

private fun chunkCity(world: UUID, radius: Int = 96) = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Town", world,
    CivPosition(0, 64, 0), radius, "nordic", Stock(), emptyList(), emptyList())

private fun chunkRuntime(limit: Int = 2048, run: (ChunkRuntime) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val fixture = ChunkRuntime(paper, limit)
        try { run(fixture) } finally { fixture.runtime.close() }
    }
}

private class ChunkRuntime(paper: MockBukkitTestRuntime, limit: Int) {
    private val host = paper.createSimplePlugin("ChunkRuntimeTest")
    private val plugin = mockk<Plugin>(relaxed = true)
    private val server = mockk<Server>(relaxed = true)
    val world = mockk<World>(relaxed = true)
    val chunk = mockk<Chunk>()
    var now = 100L
    var allowed = true
    var permissionRead: (() -> CompletableFuture<Boolean>)? = null
    var loaded = false
    var perChunkLoads = false
    val loadedKeys = hashSetOf<PaperChunkKey>()
    private val nativeTickets = hashSetOf<PaperChunkKey>()
    var level = Chunk.LoadLevel.TICKING
    var cities = emptyList<Settlement>()
    var recoveryChunks = emptyList<Set<PaperChunkKey>>()
    val added = mutableListOf<PaperChunkKey>()
    val removed = mutableListOf<PaperChunkKey>()
    var failRelease: PaperChunkKey? = null
    var failAcquire: PaperChunkKey? = null
    val loads = linkedMapOf<PaperChunkKey, CompletableFuture<Chunk?>>()
    val runtime: CivsChunkLoading
    lateinit var registry: PaperChunkTicketRegistry
        private set

    init {
        val id = UUID.randomUUID()
        every { world.uid } returns id
        every { world.name } returns "vanilla"
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers {
            val key = PaperChunkKey(id, firstArg(), arg(1))
            if (perChunkLoads) key in loadedKeys || key in nativeTickets else loaded
        }
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
        every { chunk.loadLevel } answers { level }
        every { plugin.server } returns server
        every { plugin.isEnabled } returns true
        every { plugin.logger } returns host.logger
        every { server.getWorld(id) } returns world
        every { server.pluginManager } returns paper.server.pluginManager
        val permissions = CivsChunkPermissions({ true }, { _, _ ->
            permissionRead?.invoke() ?: CompletableFuture.completedFuture(allowed)
        }, { now })
        val backend = object : PaperChunkTicketBackend {
            override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
                check(org.bukkit.Bukkit.isPrimaryThread())
                check(world.isChunkLoaded(key.x, key.z))
                check(key != failAcquire) { "Fixture ticket acquisition failure" }
                added += key
                nativeTickets += key
                return PaperChunkTicketAddResult.ADDED
            }
            override fun remove(key: PaperChunkKey): Boolean {
                removed += key
                check(key != failRelease) { "Fixture ticket release failure" }
                nativeTickets -= key
                return true
            }
        }
        runtime = CivsChunkLoading(plugin, limit, permissions, { PaperChunkTicketRegistry(backend).also { registry = it } },
            { w, x, z -> CompletableFuture<Chunk?>().also { loads[PaperChunkKey(w.uid, x, z)] = it } }, { now }, { 0L })
    }

    fun completeLoad(key: PaperChunkKey, evictUnticketed: Boolean) {
        loadedKeys += key
        loads.getValue(key).complete(chunk) shouldBe true
        if (evictUnticketed && key !in nativeTickets) loadedKeys -= key
    }
    fun completeLoads(evictUnticketed: Boolean) {
        loads.filterValues { !it.isDone }.keys.toList().forEach { completeLoad(it, evictUnticketed) }
    }
    fun pulse() { runtime.pulse(cities, recoveryChunks) }
}
