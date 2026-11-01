package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A fixed operation clock measures contention and the handoff into movement, never machine speed. */
class CivsNavigationHandoffLatencyTest : FunSpec({
    for ((kind, parallel) in listOf("flat" to 1, "flat" to 32, "maze" to 8, "mine" to 8, "corridor" to 8)) {
        test("chunk snapshots preserve $kind routes and release $parallel actors into motion") {
            val old = handoffSample(kind, parallel, false)
            val current = handoffSample(kind, parallel, true)
            val samples = listOf(old, current)
            // Bulk capture must not buy latency by skipping terrain, policy or physical traversal.
            samples.forEach { sample ->
                sample.arrived shouldBe parallel
                (sample.maximumReadyToMotionTicks <= 8) shouldBe true
            }
            current.capturedBlocks shouldBe old.capturedBlocks
            current.paths shouldBe old.paths
            if (kind == "flat" && parallel == 32) {
                withClue("legacy=$old selected=$current") {
                    (current.maximumFirstMotionTick < old.maximumFirstMotionTick) shouldBe true
                    (current.nativeBlockReads < old.nativeBlockReads) shouldBe true
                }
            }
        }
    }
})

private data class HandoffSample(val maximumReadyTick: Long, val maximumFirstMotionTick: Long,
    val maximumReadyToMotionTicks: Long, val capturedBlocks: Int, val policyCalls: Int,
    val nativeShapeReads: Int, val nativeBlockReads: Int, val workerSlices: Int, val arrived: Int,
    val paths: List<List<CitizenPoint>>)

private fun handoffSample(kind: String, parallel: Int, bulk: Boolean): HandoffSample =
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val ownerThread = Thread.currentThread()
        var nativeBlockReads = 0
        val world = object : CivsWorksiteWorldFixture("handoff-$kind-$parallel-$bulk") {
            override fun getBlockAt(x: Int, y: Int, z: Int): org.mockbukkit.mockbukkit.block.BlockMock {
                check(Thread.currentThread() === ownerThread) { "Worker touched live World/Block" }
                nativeBlockReads++
                return super.getBlockAt(x, y, z)
            }
        }.also(paper.server::addWorld)
        for (x in -1..4) for (z in -1..2) world.loadChunk(x, z)
        val start = Location(world, .5, 64.0, .5, -90f, 0f)
        val target = when (kind) {
            "maze" -> Location(world, 32.5, 64.0, .5)
            "mine" -> Location(world, 24.5, 67.0, .5)
            "corridor" -> Location(world, 24.5, 64.0, 16.5)
            else -> Location(world, 48.5, 64.0, .5)
        }
        if (kind == "maze") for ((index, x) in listOf(8, 16, 24).withIndex()) {
            val opening = if (index % 2 == 0) 3 else -3
            for (z in -4..4) if (z != opening) for (y in 64..67) world.getBlockAt(x, y, z).type = Material.STONE
        }
        if (kind == "mine") {
            for (x in -1..25) for (z in -1..1) for (y in 64..70) world.getBlockAt(x, y, z).type = Material.STONE
            for (x in 1..24) for (y in 67..68) world.getBlockAt(x, y, 0).type = Material.AIR
            for (y in 64..68) world.getBlockAt(0, y, 0).blockData =
                Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
        }
        val road = if (kind == "corridor") ((0..24).map { CivPosition(it, 63, 0) } +
            (0..16).map { CivPosition(24, 63, it) }).toSet() else emptySet()
        road.forEach { world.getBlockAt(it.x, it.y, it.z).type = Material.COBBLESTONE }
        val corridor = if (kind == "corridor") requireNotNull(CitizenSearchCorridor.fromWalked(road,
            CitizenPoint(start.x, start.y, start.z), CitizenPoint(target.x, target.y, target.z))) else null
        fun allowed(point: CivPosition): Boolean = when (kind) {
            "mine" -> point.x in 0..24 && point.z == 0 && point.y in 64..67
            "maze" -> point.x in 0..32 && point.z in -4..4 && point.y == 64
            "corridor" -> point.x in -2..26 && point.z in -2..18 && point.y == 64
            else -> point.x in -2..50 && point.z in -6..6 && point.y == 64
        }
        val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("HandoffLatency")))
        val queue = BudgetedTaskQueue(tasks, maximumSteps = 512, maximumNanos = 4_000_000L, nanoTime = { 0L })
        val workers = ArrayDeque<Runnable>()
        var workerSlices = 0
        val workerThread = Executors.newSingleThreadExecutor()
        val snapshotCache = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
        val owner = CivsResidentNavigation(tasks, queue, Executor { workerSlices++; workers.addLast(it) },
            snapshotCache = snapshotCache)
        var tick = 0L
        var policyCalls = 0
        var otherLaneSteps = 0
        queue.drainAvailable { tick }
        repeat(2) { queue.submit({ true }) { otherLaneSteps++; false } }
        nativeBlockReads = 0
        val pointPolicy: (CivPosition) -> Boolean = { policyCalls++; allowed(it) }
        val edgePolicy: (CitizenPoint, CitizenPoint) -> Boolean = { _, _ -> policyCalls++; true }
        val cost: (org.bukkit.block.Block) -> Double = {
            if (kind == "corridor" && it.type == Material.COBBLESTONE) 1.0 else CivsPaths.ORDINARY_COST
        }
        val policy = CitizenSnapshotPolicy({ p, _ -> pointPolicy(p) }, edgePolicy)
        val routes = List(parallel) {
            if (bulk) owner.request(world, start, target, pointPolicy, edgePolicy, cost, corridor, policy)
            else owner.request(world, start, target, pointPolicy, edgePolicy, cost, corridor)
        }
        val locations = List(parallel) { start.clone() }.toMutableList()
        val readyAt = LongArray(parallel) { -1 }
        val movedAt = LongArray(parallel) { -1 }
        try {
            while (movedAt.any { it < 0 } && tick < 4000) {
                while (workers.isNotEmpty()) CompletableFuture.runAsync(workers.removeFirst(), workerThread).get(20, TimeUnit.SECONDS)
                tick++
                paper.performTicks(1)
                owner.pulse()
                // Match the native frame order: poll completed planning, then advance motion.
                routes.forEachIndexed { index, route ->
                    if (!route.searching && readyAt[index] < 0) readyAt[index] = tick
                    withClue("$kind bulk=$bulk actor=$index tick=$tick phase=${route.preparationPhase}") {
                        route.failure shouldBe null
                    }
                    if (readyAt[index] >= 0 && movedAt[index] < 0) {
                        val previous = locations[index]
                        route.move(previous, 1.0) { true }?.let { next ->
                            if (next.distanceSquared(previous) > .00000001) movedAt[index] = tick
                            locations[index] = next
                        }
                    }
                }
            }
            withClue("$kind bulk=$bulk ready=${readyAt.toList()} moved=${movedAt.toList()}") {
                movedAt.all { it >= 0 } shouldBe true
            }
            val capturePolicyCalls = policyCalls
            val captureNativeReads = nativeBlockReads
            val captured = routes.sumOf { it.capturedBlocks }
            val shapes = routes.sumOf { it.nativeShapeReads }
            var arrived = 0
            val paths = mutableListOf<List<CitizenPoint>>()
            routes.forEachIndexed { index, route ->
                route.continuation shouldBe false
                route.endpoint shouldBe CitizenPoint(target.x, target.y, target.z)
                var at = locations[index]
                var frames = 0
                val path = mutableListOf<CitizenPoint>()
                while (!route.finished && route.failure == null && frames++ < 2000) {
                    route.destination?.let { CitizenPoint(it.x, it.y, it.z) }?.let { if (path.lastOrNull() != it) path += it }
                    route.move(at, 1.0) { true }?.let { next ->
                        citizenPacketBodyClear(world, CitizenPoint(next.x, next.y, next.z), false) shouldBe true
                        allowed(CivPosition(next.blockX, next.blockY, next.blockZ)) shouldBe true
                        at = next
                    }
                }
                withClue("$kind bulk=$bulk actor=$index frames=$frames at=$at target=$target") {
                    route.failure shouldBe null
                    route.finished shouldBe true
                    at.distanceSquared(target) shouldBe 0.0
                }
                arrived++
                paths += path
            }
            (otherLaneSteps > 0) shouldBe true
            HandoffSample(readyAt.max(), movedAt.max(), readyAt.indices.maxOf { movedAt[it] - readyAt[it] },
                captured, capturePolicyCalls, shapes, captureNativeReads, workerSlices, arrived, paths).also {
                println("navigation-handoff kind=$kind parallel=$parallel bulk=$bulk ${it.copy(paths = emptyList())} snapshot=${snapshotCache.debug()}")
            }
        } finally { owner.clear(); queue.close(); tasks.close(); workerThread.shutdownNow() }
    }
