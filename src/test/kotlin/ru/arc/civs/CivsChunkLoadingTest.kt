package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsChunkLoadingTest : FunSpec({
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
            h.level = Chunk.LoadLevel.ENTITY_TICKING
            h.pulse()
            h.runtime.active(city.id) shouldBe true
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
            h.loads.size shouldBe 4
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
            old.size shouldBe 4
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
    var loaded = false
    var level = Chunk.LoadLevel.TICKING
    var cities = emptyList<Settlement>()
    val added = mutableListOf<PaperChunkKey>()
    val removed = mutableListOf<PaperChunkKey>()
    val loads = linkedMapOf<PaperChunkKey, CompletableFuture<Chunk?>>()
    val runtime: CivsChunkLoading

    init {
        val id = UUID.randomUUID()
        every { world.uid } returns id
        every { world.name } returns "vanilla"
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
        every { chunk.loadLevel } answers { level }
        every { plugin.server } returns server
        every { plugin.isEnabled } returns true
        every { plugin.logger } returns host.logger
        every { server.getWorld(id) } returns world
        every { server.pluginManager } returns paper.server.pluginManager
        val permissions = CivsChunkPermissions({ true }, { _, _ -> CompletableFuture.completedFuture(allowed) }, { now })
        val backend = object : PaperChunkTicketBackend {
            override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
                added += key
                return PaperChunkTicketAddResult.ADDED
            }
            override fun remove(key: PaperChunkKey): Boolean { removed += key; return true }
        }
        runtime = CivsChunkLoading(plugin, limit, permissions, { PaperChunkTicketRegistry(backend) },
            { w, x, z -> CompletableFuture<Chunk?>().also { loads[PaperChunkKey(w.uid, x, z)] = it } }, { now }, { 0L })
    }

    fun pulse() { runtime.pulse(cities) }
}
