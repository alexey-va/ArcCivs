package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor

class CivsNavigationTerrainTest : FunSpec({
    val stone = CitizenTerrainShape(false, false, false, false, listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
    val air = stone.copy(boxes = emptyList())
    test("repeated routes reuse shapes and native changes invalidate both sides of a chunk boundary") {
        val cache = CivsNavigationTerrain(256, 100)
        val world = UUID(1, 1)
        val a = CivPosition(15, 64, 0)
        val b = a.copy(x = 16)
        var captures = 0
        fun read(p: CivPosition, tick: Long, shape: CitizenTerrainShape = stone) =
            cache.read(world, p, tick) { captures++; shape }
        read(a, 0) shouldBe stone
        read(b, 0) shouldBe stone
        repeat(20) { read(a, 50); read(b, 50) }
        captures shouldBe 2
        cache.changed(world, a)
        read(a, 51, air) shouldBe air
        read(b, 51, air) shouldBe air
        captures shouldBe 4
        read(a, 100) shouldBe stone
        captures shouldBe 5
        cache.removeChunk(world, 0, 0)
        read(b, 101) shouldBe stone
        captures shouldBe 6
    }
    test("cache is bounded and separates worlds; unloading and closing discard retained shapes") {
        val cache = CivsNavigationTerrain(2, 100)
        val world = UUID(1, 2)
        val other = UUID(1, 3)
        val p = CivPosition(0, 64, 0)
        cache.read(world, p, 0) { stone }
        cache.read(other, p, 0) { air } shouldBe air
        cache.read(world, p.copy(x = 32), 0) { stone }
        cache.debug()["blocks"] shouldBe 2
        cache.read(world, p, 0) { air } shouldBe air
        cache.removeWorld(world)
        cache.debug()["blocks"] shouldBe 0
        cache.read(other, p, 0) { stone }
        cache.clear()
        cache.debug()["blocks"] shouldBe 0
    }
    test("warm routes retain fresh permissions and changed world geometry") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("warm-navigation").also(paper.server::addWorld)
            for (x in -1..2) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("WarmNavigation")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 10000, nanoTime = { 0L })
            val worker = ArrayDeque<Runnable>()
            val owner = CivsResidentNavigation(tasks, work, Executor { worker.addLast(it) })
            fun finish(route: CitizenPacketRoute): CitizenPacketRoute {
                repeat(60) {
                    while (worker.isNotEmpty()) worker.removeFirst().run()
                    paper.performTicks(1)
                    owner.pulse()
                    if (!route.searching) return route
                }
                error("route did not finish: ${owner.debug()}")
            }
            fun route(allowed: (CivPosition) -> Boolean = { it.y == 64 && it.z == 0 && it.x in 0..20 }) =
                finish(owner.request(world, Location(world, .5, 64.0, .5), Location(world, 20.5, 64.0, .5),
                    allowed, { _, _ -> true }, { 1.0 }))
            fun misses() = (owner.debug().getValue("terrain_cache") as Map<*, *>)["misses"]
            try {
                route().failure shouldBe null
                val captured = misses()
                val repeated = route()
                repeated.failure shouldBe null
                repeated.nativeShapeReads shouldBe 0
                misses() shouldBe captured
                val denied = route { it.y == 64 && it.z == 0 && it.x in 0..20 && it.x != 10 }
                denied.failure shouldBe null
                denied.continuationReason shouldBe "no-path"
                denied.endpoint shouldBe CitizenPoint(9.5, 64.0, .5)
                val warmed = route()
                warmed.nativeShapeReads shouldBe 0
                val replanned = finish(owner.replan(warmed, Location(world, .5, 64.0, .5)))
                replanned.failure shouldBe null
                // Only the start/endpoint neighbourhoods changed, not the nine surrounding chunks.
                (replanned.nativeShapeReads <= 90) shouldBe true
                world.getBlockAt(10, 64, 0).type = Material.STONE
                world.getBlockAt(10, 65, 0).type = Material.STONE
                var at = Location(world, .5, 64.0, .5)
                repeat(160) { warmed.move(at, 1.0) { true }?.let { at = it } }
                (at.x < 10) shouldBe true
                owner.changed(world.uid, CivPosition(10, 64, 0))
                val blocked = route()
                blocked.failure shouldBe null
                blocked.continuationReason shouldBe "no-path"
                blocked.endpoint shouldBe CitizenPoint(9.5, 64.0, .5)
                blocked.blockedPositions.isNotEmpty() shouldBe true
                // A partial no-path also refreshes its frontier when the planner job retires.
                // Plugins can remove a physical blocker without firing a native event.
                // Refresh the failed frontier, retaining the rest of the learned corridor.
                world.getBlockAt(10, 64, 0).type = Material.AIR
                world.getBlockAt(10, 65, 0).type = Material.AIR
                val reopened = route()
                reopened.failure shouldBe null
                (reopened.nativeShapeReads <= 64) shouldBe true
            } finally { owner.clear(); work.close(); tasks.close() }
        }
    }
})
