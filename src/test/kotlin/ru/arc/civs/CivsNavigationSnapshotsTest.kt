package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CivsNavigationSnapshotsTest : FunSpec({
    test("registered player block listeners ignore cancellation and batch accepted edits") {
        residentRuntime { h ->
            h.assignFarm()
            h.owner.pulse(2_000)
            repeat(100) { h.navigate() }
            fun cache() = (h.owner.debug(null, null).getValue("planner_global") as Map<*, *>)["snapshot_cache"] as Map<*, *>
            val captured = cache()["chunk_captures"] as Long
            (captured > 0) shouldBe true
            val block = h.world.getBlockAt(8, 63, 8)
            val player = h.paper.server.addPlayer()
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockBreakEvent(block, player).also { it.isCancelled = true })
            cache()["dirty_chunks"] shouldBe 0
            repeat(100) { h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockBreakEvent(block, player)) }
            cache()["dirty_chunks"] shouldBe 1
            cache()["chunk_captures"] shouldBe captured
            block.type = Material.AIR // Bukkit applies the native edit after the event listeners return.
            h.paper.performTicks(2)
            cache()["chunk_captures"] shouldBe captured + 1
            cache()["refresh_batches"] shouldBe 1L
        }
    }

    test("piston listeners refresh empty head changes and the actual retract destination") {
        residentRuntime { h ->
            h.assignFarm()
            h.owner.pulse(2_000)
            repeat(100) { h.navigate() }
            fun cache() = (h.owner.debug(null, null).getValue("planner_global") as Map<*, *>)["snapshot_cache"] as Map<*, *>
            val captured = cache()["chunk_captures"] as Long
            val changed = hashSetOf<CivPosition>()
            h.owner.worksiteTerrainChanged = { _, p -> changed += p }
            val east = org.bukkit.block.BlockFace.EAST
            val base = h.world.getBlockAt(8, 64, 8)
            base.type = Material.PISTON
            base.blockData = (base.blockData as org.bukkit.block.data.Directional).also { it.facing = east }
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonExtendEvent(base, emptyList(), east)
                .also { it.isCancelled = true })
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonRetractEvent(base, emptyList(), east)
                .also { it.isCancelled = true })
            changed shouldBe emptySet()
            cache()["dirty_chunks"] shouldBe 0

            repeat(100) { h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonExtendEvent(base, emptyList(), east)) }
            changed shouldBe setOf(CivPosition(8, 64, 8), CivPosition(9, 64, 8))
            cache()["dirty_chunks"] shouldBe 1
            cache()["chunk_captures"] shouldBe captured
            base.getRelative(east).type = Material.PISTON_HEAD
            h.paper.performTicks(2)
            cache()["chunk_captures"] shouldBe captured + 1

            changed.clear()
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonRetractEvent(base, emptyList(), east))
            changed shouldBe setOf(CivPosition(8, 64, 8), CivPosition(9, 64, 8))
            base.getRelative(east).type = Material.AIR
            h.paper.performTicks(2)
            cache()["chunk_captures"] shouldBe captured + 2

            changed.clear()
            val edge = h.world.getBlockAt(14, 64, 8)
            edge.type = Material.STICKY_PISTON
            edge.blockData = (edge.blockData as org.bukkit.block.data.Directional).also { it.facing = east }
            val moved = h.world.getBlockAt(16, 64, 8).also { it.type = Material.STONE }
            // Paper reports movement (west) when retracting a block, but facing (east) for an empty retract.
            h.paper.server.pluginManager.callEvent(org.bukkit.event.block.BlockPistonRetractEvent(edge, listOf(moved), east.oppositeFace))
            changed shouldBe setOf(CivPosition(14, 64, 8), CivPosition(15, 64, 8), CivPosition(16, 64, 8))
        }
    }

    test("whole chunk snapshots keep negative coordinates and detached contents after native edits") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val main = Thread.currentThread()
            val world = object : CivsWorksiteWorldFixture("negative-snapshot") {
                override fun getBlockAt(x: Int, y: Int, z: Int): org.mockbukkit.mockbukkit.block.BlockMock {
                    check(Thread.currentThread() === main) { "Worker read live terrain" }
                    return super.getBlockAt(x, y, z)
                }
            }.also(paper.server::addWorld)
            world.loadChunk(-2, -1)
            val point = CivPosition(-17, 64, -1)
            world.getBlockAt(point.x, point.y, point.z).type = Material.COBBLESTONE
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("DetachedSnapshot")))
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            val chunk = requireNotNull(snapshots.capture(world, CitizenSnapshotKey(-2, -1), 0))
            world.getBlockAt(point.x, point.y, point.z).type = Material.AIR
            val worker = Executors.newSingleThreadExecutor()
            try {
                CompletableFuture.supplyAsync({
                    chunk.data(point).material shouldBe Material.COBBLESTONE
                    chunk.data(CivPosition(-18, 63, -2)).material shouldBe Material.STONE
                    chunk.states(61, 67).keys.map { it.material }.toSet() shouldBe
                        setOf(Material.STONE, Material.AIR, Material.COBBLESTONE)
                }, worker).get(20, TimeUnit.SECONDS)
            } finally { worker.shutdownNow(); snapshots.clear(); tasks.close() }
        }
    }

    test("one hundred block changes coalesce into one delayed batch including the opposite chunk edge") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-dirty-batch").also(paper.server::addWorld)
            val keys = listOf(CitizenSnapshotKey(-1, 0), CitizenSnapshotKey(0, 0), CitizenSnapshotKey(1, 0))
            keys.forEach { world.loadChunk(it.x, it.z) }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotBatch")))
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            try {
                val captured = keys.associateWith { requireNotNull(snapshots.capture(world, it, 0)) }
                val old = captured.mapValues { it.value.revision }
                val at = CivPosition(-1, 64, 5)
                world.getBlockAt(at.x, at.y, at.z).type = Material.STONE
                repeat(100) { snapshots.changed(world.uid, at) }
                snapshots.current(world.uid, old) shouldBe false
                snapshots.debug()["dirty_chunks"] shouldBe 2
                snapshots.debug()["chunk_captures"] shouldBe 3L
                tasks.trackedTaskCount() shouldBe 1
                shouldThrow<CitizenSnapshotPending> { snapshots.capture(world, keys.first(), 0) }
                paper.performTicks(1)
                snapshots.debug()["chunk_captures"] shouldBe 3L
                paper.performTicks(1)
                snapshots.debug()["chunk_captures"] shouldBe 5L
                snapshots.debug()["refresh_batches"] shouldBe 1L
                snapshots.debug()["dirty_chunks"] shouldBe 0
                snapshots.current(world.uid, old) shouldBe false
                val fresh = requireNotNull(snapshots.capture(world, keys.first(), 2))
                fresh.data(at).material shouldBe Material.STONE
                captured.getValue(keys.first()).data(at).material shouldBe Material.AIR
                snapshots.capture(world, keys.last(), 2) shouldBe captured.getValue(keys.last())
                // Re-loading an evicted generation must never make a retired worker snapshot current again.
                snapshots.removeChunk(world.uid, -1, 0)
                snapshots.capture(world, keys.first(), 3)
                snapshots.current(world.uid, mapOf(keys.first() to old.getValue(keys.first()))) shouldBe false
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("palette shapes are reused while contextual collision boxes remain exact per position") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-context-shapes").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotShapes")))
            val owner = Thread.currentThread()
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks,
                contextFree = { it.material != Material.SNOW }, shape = { _, _, p ->
                    check(Thread.currentThread() === owner)
                    listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, if (p.x == 2) .25 else .75, 1.0))
                })
            try {
                val stone = world.getBlockAt(0, 63, 0).blockData
                snapshots.state(world, stone, CivPosition(0, 63, 0)).shape.boxes.single().maxY shouldBe .75
                snapshots.state(world, stone, CivPosition(1, 63, 0)).shape.boxes.single().maxY shouldBe .75
                snapshots.debug()["palette_shape_reads"] shouldBe 1L
                for (x in 2..3) world.getBlockAt(x, 64, 0).type = Material.SNOW
                val data = world.getBlockAt(2, 64, 0).blockData
                snapshots.state(world, data, CivPosition(2, 64, 0)).dynamic shouldBe true
                snapshots.dynamic(world, data, CivPosition(2, 64, 0)).single().maxY shouldBe .25
                snapshots.dynamic(world, data, CivPosition(3, 64, 0)).single().maxY shouldBe .75
                snapshots.debug()["dynamic_shape_reads"] shouldBe 2L
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("LRU eviction retains active generations while TTL expiry and lifecycle retirement invalidate them") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-lru").also(paper.server::addWorld)
            world.loadChunk(0, 0); world.loadChunk(1, 0)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotLru")))
            val snapshots = CivsNavigationSnapshots(4096, 5, tasks, contextFree = { true })
            try {
                val a = CitizenSnapshotKey(0, 0)
                val b = CitizenSnapshotKey(1, 0)
                val first = requireNotNull(snapshots.capture(world, a, 0))
                snapshots.capture(world, b, 0)
                snapshots.debug()["chunks"] shouldBe 1
                snapshots.capture(world, a, 1)?.revision shouldBe first.revision
                snapshots.current(world.uid, mapOf(a to first.revision)) shouldBe true
                snapshots.capture(world, a, 6)
                snapshots.current(world.uid, mapOf(a to first.revision)) shouldBe false

                val workers = ArrayDeque<Runnable>()
                val owner = CivsResidentNavigation(tasks, executor = Executor { workers.addLast(it) }, snapshotCache = snapshots)
                val policy = CitizenSnapshotPolicy({ p, _ -> p.y == 64 }, { _, _ -> true })
                val route = owner.request(world, Location(world, 2.5, 64.0, 2.5), Location(world, 8.5, 64.0, 2.5),
                    { it.y == 64 }, policy.stepAllowed, { 1.0 }, null, policy)
                workers.removeFirst().run(); owner.pulse()
                route.preparationPhase shouldBe "snapshot-processing"
                owner.replan(route, Location(world, 2.5, 64.0, 2.5),
                    listOf(BoundingBox(4.0, 64.0, 4.0, 4.6, 65.8, 4.6)))
                snapshots.debug()["dirty_chunks"] shouldBe 0 // A resident body is not a native block edit.
                owner.clear()
                while (workers.isNotEmpty()) workers.removeFirst().run()
                paper.performTicks(3)
                owner.debug()["jobs"] shouldBe 0
                route.preparationPhase shouldBe "cancelled"
                snapshots.debug()["chunks"] shouldBe 0
            } finally { snapshots.clear(); tasks.close() }
        }
    }

    test("a block change between async solving and Main acceptance rejects the stale path") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("snapshot-stale-route").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SnapshotRace")))
            val workers = ArrayDeque<Runnable>()
            val snapshots = CivsNavigationSnapshots(131072, 6000, tasks, contextFree = { true })
            val owner = CivsResidentNavigation(tasks, executor = Executor { workers.addLast(it) }, snapshotCache = snapshots)
            val allowed: (CivPosition) -> Boolean = { it.x in 0..20 && it.y == 64 && it.z == 0 }
            val policy = CitizenSnapshotPolicy({ p, _ -> allowed(p) }, { _, _ -> true })
            val route = owner.request(world, Location(world, .5, 64.0, .5), Location(world, 20.5, 64.0, .5),
                allowed, policy.stepAllowed, { 1.0 }, null, policy)
            try {
                var tick = 0
                while (!(route.capturedBlocks > 400 && route.preparationPhase == "searching") && tick++ < 100) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                route.searching shouldBe true
                (route.capturedBlocks > 400) shouldBe true
                // The next worker result uses the old graph; publish the obstacle before Main can accept it.
                while (workers.isNotEmpty()) workers.removeFirst().run()
                for (y in 64..66) {
                    world.getBlockAt(8, y, 0).type = Material.STONE
                    owner.changed(world.uid, CivPosition(8, y, 0))
                }
                owner.pulse()
                owner.debug()["stale_snapshot_restarts"] shouldBe 1L
                while (route.searching && tick++ < 250) {
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    paper.performTicks(1); owner.pulse()
                }
                withClue("${owner.debug()} phase=${route.preparationPhase}") {
                    route.searching shouldBe false
                    route.failure shouldBe null
                    route.continuationReason shouldBe "no-path"
                    route.endpoint shouldBe CitizenPoint(7.5, 64.0, .5)
                }
                snapshots.changed(world.uid, CivPosition(0, 64, 0))
                owner.clear()
                paper.performTicks(3)
                snapshots.debug()["chunks"] shouldBe 0
                snapshots.debug()["dirty_chunks"] shouldBe 0
            } finally { owner.clear(); tasks.close() }
        }
    }
})
