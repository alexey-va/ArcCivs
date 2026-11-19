package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.bukkit.ChunkSnapshot
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CivsNavigationSectionCacheTest : FunSpec({
    test("two city quotas share physical payload once and retirement releases only the retired membership") {
        SectionHarness(cityMaximum = 2, globalMaximum = 4).use { h ->
            val a = h.city.id; val b = UUID(0, 2)
            val first = h.extract(CitizenSnapshotKey(0, 0), setOf(3, 4), a)
            h.extract(CitizenSnapshotKey(1, 0), setOf(3, 4), b)
            h.cache.debug()["sections"] shouldBe 4
            h.cache.debug()["index_payload_bytes"] shouldBe 32768L
            h.cityStats(a)["evictions"] shouldBe 0L
            h.cityStats(b)["evictions"] shouldBe 0L
            val copies = h.cache.debug()["chunk_captures"]
            h.cache.cached(h.world, listOf(CitizenSnapshotSectionKey(0, 4, 0)), 0, b)
            h.cache.debug()["chunk_captures"] shouldBe copies
            h.cache.debug()["sections"] shouldBe 3
            h.cache.debug()["index_payload_bytes"] shouldBe 24576L
            h.cityStats(a)["shared_sections"] shouldBe 1
            h.cityStats(b)["shared_sections"] shouldBe 1
            h.cache.retainCities(setOf(b))
            h.cache.debug()["cities_total"] shouldBe 1
            h.cache.debug()["sections"] shouldBe 2
            h.cache.currentSections(h.world.uid, first.payloads().associate { it.key to it.revision }) shouldBe true
            h.cache.retainCities(emptySet())
            h.cache.debug()["sections"] shouldBe 0
        }
    }

    test("a first height miss in a known chunk is distinct from physical city and global eviction") {
        SectionHarness(cityMaximum = 2, globalMaximum = 2).use { h ->
            val key = CitizenSnapshotKey(0, 0)
            h.extract(key, setOf(3))
            h.extract(key, setOf(4))
            h.cache.debug()["chunk_captures_known_chunk_section_miss"] shouldBe 1L
            h.cache.debug()["chunk_captures_lru"] shouldBe 1L // Deprecated compatibility alias.
            h.cache.debug()["physical_section_evictions"] shouldBe 0L
            h.extract(CitizenSnapshotKey(1, 0), setOf(3))
            h.cache.debug()["physical_section_evictions"] shouldBe 1L
            h.cityStats(h.city.id)["evictions"] shouldBe 1L
            val second = UUID(0, 2)
            h.extract(CitizenSnapshotKey(2, 0), setOf(3), second)
            h.cache.debug()["physical_section_evictions"] shouldBe 2L
            h.cityStats(h.city.id)["evictions"] shouldBe 2L
            h.cache.retainCities(setOf(second))
            h.cache.removeChunk(h.world.uid, 2, 0)
            h.cache.debug()["physical_section_evictions"] shouldBe 2L
        }
    }

    test("a deduplicated seed batch keeps returned immutable sections under a smaller city quota") {
        SectionHarness(cityMaximum = 1, globalMaximum = 4).use { h ->
            val first = h.extract(CitizenSnapshotKey(0, 0), setOf(4)).payloads().single()
            val second = h.extract(CitizenSnapshotKey(1, 0), setOf(4), UUID(0, 2)).payloads().single()
            val route = h.route()
            val probes = listOf(1, 17, 1).map { x ->
                val node = CitizenSearchNode(x, 64.0, 1)
                CitizenSearchProbe(node, node.point())
            }
            val input = route.seedSnapshots(h.cache, probes, false)
            input.seeded shouldBe listOf(first, second)
            input.seeded[0] shouldBeSameInstanceAs first
            input.seeded[1] shouldBeSameInstanceAs second
            h.cityStats(h.city.id)["sections"] shouldBe 1
            (h.cache.debug()["sections"] as Int <= 4) shouldBe true
            val stamps = input.seeded.associate { it.key to it.revision }
            h.cache.currentSections(h.world.uid, stamps) shouldBe true
            h.cache.changed(h.world.uid, CivPosition(1, 64, 1))
            h.paper.performTicks(3)
            h.cache.currentSections(h.world.uid, stamps) shouldBe false
            input.seeded shouldBe listOf(first, second)
        }
    }

    test("TTL refresh establishes one new generation and a new height does not expire its fresh sibling") {
        SectionHarness(refreshTicks = 5).use { h ->
            val key = CitizenSnapshotKey(0, 0)
            val first = h.extract(key, setOf(4))
            val refreshed = h.extract(key, setOf(4), tick = 5)
            val lower = h.extract(key, setOf(2), tick = 6)
            lower.revision shouldBe refreshed.revision
            h.cache.currentSections(h.world.uid, first.payloads().associate { it.key to it.revision }) shouldBe false
            h.cache.currentSections(h.world.uid, refreshed.payloads().associate { it.key to it.revision }) shouldBe true
            h.cache.cached(h.world, listOf(CitizenSnapshotSectionKey(0, 4, 0)), 6, h.city.id)
                .getValue(key).payloads().single() shouldBeSameInstanceAs refreshed.payloads().single()
            h.cache.debug()["chunk_captures_ttl"] shouldBe 1L
            h.cache.removeChunk(h.world.uid, 0, 0)
            h.cache.publish(h.world.uid, lower.payloads(), h.city.id)
            h.cache.debug()["sections"] shouldBe 0 // A late worker cannot restore the unloaded generation.
        }
    }

    test("unload skips unrelated chunks but retires unpublished neighbours and shared nearby sections") {
        SectionHarness().use { h ->
            val pending = requireNotNull(h.cache.capture(h.world, CitizenSnapshotKey(-1, 0), 0, setOf(4), h.city.id))
            try { pending.prepare() } finally { pending.releaseSource() }
            h.cache.debug()["sections"] shouldBe 0
            h.cache.removeChunk(h.world.uid, 0, 0)
            h.cache.currentSections(h.world.uid, pending.payloads().associate { it.key to it.revision }) shouldBe false
            h.cache.publish(h.world.uid, pending.payloads(), h.city.id)
            h.cache.debug()["sections"] shouldBe 0

            val a = h.city.id; val b = UUID(0, 2)
            val centre = h.extract(CitizenSnapshotKey(0, 0), setOf(3, 4), a)
            val neighbour = h.extract(CitizenSnapshotKey(1, 1), setOf(4), b)
            val far = h.extract(CitizenSnapshotKey(4, 0), setOf(3, 4), a)
            h.cache.cached(h.world, listOf(CitizenSnapshotSectionKey(0, 4, 0)), 0, b)
            val before = h.cache.retainedPayloads()
            val stamps = before.associate { it.key to it.revision }
            h.cache.removeChunk(h.world.uid, 100, 100)
            h.cache.retainedPayloads() shouldBe before
            h.cache.currentSections(h.world.uid, stamps) shouldBe true

            h.cache.removeChunk(h.world.uid, 0, 0)
            h.cache.retainedPayloads() shouldBe before.filter { it.key.x == 4 }
            h.cityStats(a)["sections"] shouldBe 2
            h.cityStats(b)["sections"] shouldBe 0
            h.cityStats(a)["evictions"] shouldBe 0L
            h.cityStats(b)["evictions"] shouldBe 0L
            h.cache.debug()["physical_section_evictions"] shouldBe 0L
            h.cache.currentSections(h.world.uid, far.payloads().associate { it.key to it.revision }) shouldBe true
            h.cache.publish(h.world.uid, centre.payloads(), a)
            h.cache.publish(h.world.uid, neighbour.payloads(), b)
            h.cache.retainedPayloads() shouldBe before.filter { it.key.x == 4 }
            // The unpublished -1/0, centre, and 1/1 generations retain their original dx/dz order.
            h.extract(CitizenSnapshotKey(1, 1), setOf(4), b).revision shouldBe far.revision + 3
        }
    }

    test("raw copies are released on extraction and cancellation and are absent from retained section graphs") {
        SectionHarness().use { h ->
            val key = CitizenSnapshotKey(0, 0)
            val chunk = h.extract(key, setOf(3, 4))
            chunk.retainsNativeSnapshot shouldBe false
            noRawSnapshot(chunk)
            noRawSnapshot(h.cache.retainedPayloads())
            val input = requireNotNull(h.cache.capture(h.world, key, 0, setOf(6), h.city.id))
            input.retainsNativeSnapshot shouldBe true
            val node = CitizenSearchNode(1, 96.0, 1)
            val cursor = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), CitizenSnapshotTerrain(),
                CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true }), -64, 320, 32768, 256, false)
            cursor.cancel()
            runCatching { cursor.advance(CitizenSnapshotInput(chunks = mapOf(key to input))) }.isFailure shouldBe true
            input.retainsNativeSnapshot shouldBe false
        }
    }

    test("an unavailable reply for a previously consumed partial chunk terminates instead of requesting its missing height forever") {
        SectionHarness().use { h ->
            val key = CitizenSnapshotKey(0, 0)
            val chunk = h.extract(key, setOf(4))
            val terrain = CitizenSnapshotTerrain().also { it.chunks[key] = chunk }
            val node = CitizenSearchNode(8, 80.0, 8)
            val cursor = CitizenSnapshotCapture(listOf(CitizenSearchProbe(node, node.point())), terrain,
                CitizenSnapshotPolicy({ _, _ -> true }, { _, _ -> true }), -64, 320, 32768, 256, false)
            cursor.advance(CitizenSnapshotInput()).requestedSections shouldBe setOf(CitizenSnapshotSectionKey(0, 5, 0))
            shouldThrow<CitizenSnapshotUnavailable> { cursor.advance(CitizenSnapshotInput(unavailable = setOf(key))) }
        }
    }

    test("sparse warm bands cover a surface and authored lower floor without filling the unrelated gap") {
        SectionHarness().use { h ->
            val lower = CivBuilding(UUID(0, 20), BuildingType.HOUSE, 1, CivPosition(40, 20, 8), 0, "test",
                CivBox(CivPosition(40, 20, 8), CivPosition(48, 26, 16)), CivPosition(40, 21, 8), BuildingPhase.ACTIVE)
            val city = h.city.copy(buildings = listOf(lower))
            val plan = CitizenSnapshotWarmPlan.of(city, 8, 1024)
            plan.truncated shouldBe false
            (CitizenSnapshotSectionKey(0, 4, 0) in plan.sections) shouldBe true
            (CitizenSnapshotSectionKey(2, 1, 0) in plan.sections) shouldBe true
            (CitizenSnapshotSectionKey(0, 1, 0) in plan.sections) shouldBe false
            (CitizenSnapshotSectionKey(1, 2, 0) in plan.sections) shouldBe false
            plan.sections.any { it.y < 1 || it.y > 4 } shouldBe false
        }
    }

    test("warming covers streets between distant buildings and follows the loaded surface height") {
        SectionHarness().use { h ->
            val far = CivBuilding(UUID(0, 20), BuildingType.HOUSE, 1, CivPosition(144, 64, 8), 0, "test",
                CivBox(CivPosition(144, 64, 8), CivPosition(152, 70, 16)), CivPosition(144, 64, 8), BuildingPhase.ACTIVE)
            val city = h.city.copy(buildings = listOf(far))
            val area = CivsChunkArea.developed(city)
            for (x in area.minX..area.maxX) for (z in area.minZ..area.maxZ) h.world.loadChunk(x, z)
            h.world.getBlockAt(64, 95, 8).type = Material.STONE
            h.surfaceHeights[64 to 8] = 95
            h.warm(listOf(city))
            val keys = h.cache.retainedPayloads().mapTo(hashSetOf()) { it.key }
            keys.containsAll(listOf(CitizenSnapshotSectionKey(4, 5, 0), CitizenSnapshotSectionKey(4, 6, 0),
                CitizenSnapshotSectionKey(5, 3, 0), CitizenSnapshotSectionKey(5, 4, 0))) shouldBe true
            h.heightReads shouldBe area.size * 256
            h.heightChunks shouldBe area.keys().mapTo(hashSetOf()) { it.x to it.z }
            val copies = h.cache.debug()["chunk_captures"]
            val requests = h.owner.debug()["snapshot_main_requests"]
            h.finish(h.route(from = 80.5, to = 88.5))
            h.cache.debug()["chunk_captures"] shouldBe copies
            h.owner.debug()["snapshot_main_requests"] shouldBe requests
            noRawSnapshot(h.cache.retainedPayloads())
        }
    }

    test("authored floors precede optional centre and street warming when the city quota is tight") {
        SectionHarness(cityMaximum = 8).use { h ->
            val lower = CivBuilding(UUID(0, 20), BuildingType.HOUSE, 1, CivPosition(88, 20, 8), 0, "test",
                CivBox(CivPosition(88, 20, 8), CivPosition(96, 26, 16)), CivPosition(88, 21, 8), BuildingPhase.ACTIVE)
            h.warm(listOf(h.city.copy(buildings = listOf(lower))))
            h.cache.retainedPayloads().any { it.key == CitizenSnapshotSectionKey(5, 1, 0) } shouldBe true
            (h.cache.debug()["sections"] as Int <= 8) shouldBe true
            h.cityStats(h.city.id)["evictions"] shouldBe 0L
        }
    }

    test("footprint updates keep valid warmed surfaces and revisit a changed height outside the old band") {
        SectionHarness().use { h ->
            h.warm(listOf(h.city))
            val reads = h.heightReads
            val inside = CivBuilding(UUID(0, 20), BuildingType.HOUSE, 1, CivPosition(2, 64, 2), 0, "test",
                CivBox(CivPosition(2, 64, 2), CivPosition(6, 70, 6)), CivPosition(2, 64, 2), BuildingPhase.ACTIVE)
            val city = h.city.copy(buildings = listOf(inside))
            h.warm(listOf(city))
            h.heightReads shouldBe reads
            val raised = CivPosition(24, 95, 8)
            h.world.getBlockAt(raised.x, raised.y, raised.z).type = Material.STONE
            h.surfaceHeights[raised.x to raised.z] = raised.y
            h.cache.changed(h.world.uid, raised)
            h.paper.performTicks(3)
            val updated = city.copy(buildings = listOf(inside.copy(control = CivPosition(3, 65, 3))))
            h.warm(listOf(updated))
            h.heightReads - reads shouldBe 256L
            h.cache.retainedPayloads().any { it.key == CitizenSnapshotSectionKey(1, 6, 0) } shouldBe true
            val beforeUnload = h.heightReads
            h.owner.prepareNavigation(listOf(updated)) { false }
            h.world.unloadChunk(1, 0)
            h.cache.removeChunk(h.world.uid, 1, 0)
            h.world.loadChunk(1, 0)
            h.warm(listOf(updated))
            h.heightReads - beforeUnload shouldBe 6 * 256L // Native unload invalidates this chunk and its retained neighbours.
        }
    }

    test("unavailable warm batches stay idle and recover when their city becomes ready again") {
        SectionHarness().use { h ->
            h.world.unloadChunk(-1, -1)
            h.warm(listOf(h.city))
            h.cache.retainedPayloads().any { it.key.x == -1 && it.key.z == -1 } shouldBe false
            val copies = h.cache.debug()["chunk_captures"]
            val reads = h.heightReads
            repeat(40) { h.owner.prepareNavigation(listOf(h.city)) { true }; h.tick() }
            h.cache.debug()["chunk_captures"] shouldBe copies
            h.heightReads shouldBe reads
            h.owner.prepareNavigation(listOf(h.city)) { false }
            h.world.loadChunk(-1, -1)
            h.warm(listOf(h.city))
            h.cache.retainedPayloads().mapTo(hashSetOf()) { it.key }.containsAll(listOf(
                CitizenSnapshotSectionKey(-1, 3, -1), CitizenSnapshotSectionKey(-1, 4, -1))) shouldBe true
            val completedReads = h.heightReads
            repeat(40) { h.owner.prepareNavigation(listOf(h.city)) { true }; h.tick() }
            h.heightReads shouldBe completedReads
        }
    }

    test("surface height reads share the native step budget and resume after a queued reactivation") {
        SectionHarness(nativeSteps = 1).use { h ->
            var ticks = 0
            while (h.heightReads == 0L && ticks++ < 100) {
                h.owner.prepareNavigation(listOf(h.city)) { true }; h.tick()
            }
            h.heightReads shouldBe 16L
            repeat(4) {
                val before = h.heightReads
                h.owner.prepareNavigation(listOf(h.city)) { true }; h.tick()
                h.heightReads - before shouldBe 16L
            }
            val before = h.heightReads
            h.owner.prepareNavigation(listOf(h.city)) { false }
            h.tick() // The queue cancels; its terminal Main continuation is still waiting.
            h.owner.prepareNavigation(listOf(h.city)) { true }
            ticks = 0
            while (h.heightReads == before && ticks++ < 100) {
                val reads = h.heightReads
                h.owner.prepareNavigation(listOf(h.city)) { true }; h.tick()
                (h.heightReads - reads in 0L..16L) shouldBe true
            }
            (h.heightReads > before) shouldBe true
        }
    }

    test("warm ownership survives queued Main continuations without admitting a second pipeline") {
        SectionHarness().use { h ->
            h.owner.prepareNavigation(listOf(h.city)) { true }
            h.work.drainAvailable { 0L }
            h.cache.debug()["chunk_captures"] shouldBe 1L
            repeat(5) { h.owner.prepareNavigation(listOf(h.city)) { true }; h.work.drainAvailable { 0L } }
            h.cache.debug()["chunk_captures"] shouldBe 1L
            h.paper.performTicks(1) // Native future's delayed Main continuation admits the worker.
            h.workers.size shouldBe 1
            h.runWorkers()
            repeat(5) { h.owner.prepareNavigation(listOf(h.city)) { true }; h.work.drainAvailable { 1L } }
            h.cache.debug()["chunk_captures"] shouldBe 1L
            h.owner.clear()
            h.paper.performTicks(3)
            h.cache.debug()["sections"] shouldBe 0
        }
    }

    test("global capacity stops speculative warming without eviction or repeated capture churn") {
        SectionHarness(cityMaximum = 16, globalMaximum = 2).use { h ->
            val second = h.city.copy(id = UUID(0, 2), center = CivPosition(72, 64, 8))
            h.warm(listOf(h.city, second))
            (h.cache.debug()["sections"] as Int <= 2) shouldBe true
            val copies = h.cache.debug()["chunk_captures"]
            val decodes = h.cache.debug()["detached_section_decodes"]
            repeat(80) { h.owner.prepareNavigation(listOf(h.city, second)) { true }; h.tick() }
            h.cache.debug()["chunk_captures"] shouldBe copies
            h.cache.debug()["detached_section_decodes"] shouldBe decodes
            h.cityStats(h.city.id)["evictions"] shouldBe 0L
            h.cityStats(second.id)["evictions"] shouldBe 0L
            val rows = (h.owner.debug()["snapshot_warmup"] as Map<*, *>)["entries"] as List<*>
            rows.all { (it as Map<*, *>)["capacity_limited"] == true } shouldBe true
        }
    }

    test("static routes inside a warm corridor need no Main terrain callback and outside heights remain explicit misses") {
        SectionHarness().use { h ->
            h.warm(listOf(h.city))
            val copies = h.cache.debug()["chunk_captures"]
            val shapes = h.cache.debug()["palette_shape_reads"]
            val requests = h.owner.debug()["snapshot_main_requests"]
            val first = h.route(); h.finish(first)
            val second = h.route(); h.finish(second)
            first.endpoint shouldBe second.endpoint
            first.nodeCount shouldBe second.nodeCount
            first.capturedBlocks shouldBe second.capturedBlocks
            h.cache.debug()["chunk_captures"] shouldBe copies
            h.cache.debug()["palette_shape_reads"] shouldBe shapes
            h.owner.debug()["snapshot_main_requests"] shouldBe requests

            h.world.getBlockAt(2, 90, 0).type = Material.STONE
            val policy = CitizenSnapshotPolicy({ p, material -> p.y == 64 &&
                material(CivPosition(2, 90, 0)) == Material.STONE }, { _, _ -> true },
                supportCost = { _, _ -> 1.0 }, cityId = h.city.id)
            val otherHeight = h.route(policy = policy); h.finish(otherHeight)
            (h.cache.debug()["chunk_captures"] as Long > copies as Long) shouldBe true
            h.cache.retainedPayloads().any { it.key == CitizenSnapshotSectionKey(0, 5, 0) } shouldBe true
            val beforeOutside = h.cache.debug()["chunk_captures"] as Long
            val outside = h.route(from = -39.5, to = -32.5); h.finish(outside)
            (h.cache.debug()["chunk_captures"] as Long > beforeOutside) shouldBe true
        }
    }
})

private class SectionHarness(cityMaximum: Int = 1024, globalMaximum: Int = 8192, refreshTicks: Int = 6000,
    nativeSteps: Int = 10000) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open(CivsWorksiteServerFixture())
    private val ownerThread = Thread.currentThread()
    val surfaceHeights = hashMapOf<Pair<Int, Int>, Int>()
    val heightChunks = hashSetOf<Pair<Int, Int>>()
    var heightReads = 0L
    val world = object : CivsWorksiteWorldFixture("section-cache") {
        override fun getHighestBlockYAt(x: Int, z: Int, heightMap: HeightMap): Int {
            check(Thread.currentThread() === ownerThread) { "Worker read native height map" }
            check(isChunkLoaded(x shr 4, z shr 4)) { "Height map loaded an unavailable chunk" }
            heightMap shouldBe HeightMap.MOTION_BLOCKING_NO_LEAVES
            heightReads++
            heightChunks += (x shr 4) to (z shr 4)
            return surfaceHeights[x to z] ?: 63
        }
        override fun getBlockAt(x: Int, y: Int, z: Int): org.mockbukkit.mockbukkit.block.BlockMock {
            check(Thread.currentThread() === ownerThread) { "Worker read native world" }
            return super.getBlockAt(x, y, z)
        }
    }.also(paper.server::addWorld)
    val city = Settlement(UUID(0, 1), UUID(0, 10), "Sections", world.uid, CivPosition(8, 64, 8), 96,
        "fixture", Stock(), emptyList(), emptyList())
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SectionCache")))
    val work = BudgetedTaskQueue(tasks, nativeSteps, nanoTime = { 0L })
    val workers = ArrayDeque<Runnable>()
    val cache = CivsNavigationSnapshots(131072, refreshTicks, tasks, contextFree = { true },
        maximumSectionsPerCity = cityMaximum, maximumSections = globalMaximum)
    val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) },
        settings = CivsNavigationSettings(snapshotSectionsPerCity = cityMaximum, snapshotSectionsMaximum = globalMaximum,
            snapshotWarmRadius = 16), snapshotCache = cache)
    init { for (x in -3..6) for (z in -3..3) world.loadChunk(x, z) }

    fun extract(key: CitizenSnapshotKey, ys: Set<Int>, cityId: UUID = city.id, tick: Long = 0): CitizenSnapshotChunk {
        val result = requireNotNull(cache.capture(world, key, tick, ys, cityId))
        CompletableFuture.runAsync { try { result.prepare() } finally { result.releaseSource() } }.get(20, TimeUnit.SECONDS)
        cache.publish(world.uid, result.payloads(), cityId)
        return result
    }
    fun cityStats(id: UUID): Map<*, *> = (cache.debug(id)["cities"] as List<*>).single() as Map<*, *>
    fun runWorkers() { while (workers.isNotEmpty()) CompletableFuture.runAsync(workers.removeFirst()).get(20, TimeUnit.SECONDS) }
    fun tick() { runWorkers(); paper.performTicks(1); owner.pulse() }
    fun warm(cities: List<Settlement>) {
        repeat(400) {
            owner.prepareNavigation(cities) { true }; tick()
            val debug = owner.debug()["snapshot_warmup"] as Map<*, *>
            if (debug["pending"] == false && (debug["entries"] as List<*>).all {
                val row = it as Map<*, *>
                row["remaining_batches"] == (row["deferred_batches"] ?: 0) }) return
        }
        error("Warm pass did not finish: ${owner.debug()}")
    }
    fun route(from: Double = .5, to: Double = 8.5, policy: CitizenSnapshotPolicy = CitizenSnapshotPolicy(
        { p, _ -> p.y == 64 }, { _, _ -> true }, supportCost = { _, _ -> 1.0 }, cityId = city.id)): CitizenPacketRoute =
        owner.request(world, Location(world, from, 64.0, .5), Location(world, to, 64.0, .5),
            { p -> p.y == 64 }, policy.stepAllowed, { 1.0 }, null, policy)
    fun finish(route: CitizenPacketRoute) {
        repeat(300) {
            if (!route.searching) {
                withClue(owner.debug().toString()) { route.failure shouldBe null }
                return
            }
            tick()
        }
        error("Route did not finish: ${owner.debug()}")
    }
    override fun close() { owner.clear(); work.close(); tasks.close(); runWorkers(); paper.close() }
}

/** Structural reachability check on the retained navigation graph, without heap dumps or GC timing assumptions. */
private fun noRawSnapshot(root: Any) {
    val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    fun visit(value: Any?) {
        if (value == null || !seen.add(value)) return
        (value is ChunkSnapshot) shouldBe false
        when (value) {
            is Map<*, *> -> value.forEach { (key, item) -> visit(key); visit(item) }
            is Iterable<*> -> value.forEach(::visit)
            is Array<*> -> value.forEach(::visit)
            else -> if (value.javaClass.name.startsWith("ru.arc.civs.CitizenSnapshot"))
                value.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach {
                    it.isAccessible = true; visit(it.get(value))
                }
        }
    }
    visit(root)
}
