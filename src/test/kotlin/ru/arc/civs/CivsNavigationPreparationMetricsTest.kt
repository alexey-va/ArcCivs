package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor

class CivsNavigationPreparationMetricsTest : FunSpec({
    test("all planner jobs include fast success and distinguish cancellation and denied routes") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("preparation-metrics").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("PreparationMetrics")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 10_000, nanoTime = { 0L })
            val workers = ArrayDeque<Runnable>()
            var clock = 0L
            val owner = CivsResidentNavigation(tasks, work, Executor { workers.addLast(it) }, nanoTime = { clock })
            fun request(allowed: Boolean = true) = owner.request(world, Location(world, .5, 64.0, .5),
                Location(world, 3.5, 64.0, .5), { allowed && it.y == 64 && it.z == 0 && it.x in 0..3 },
                { _, _ -> allowed }, { 1.0 })
            fun finish(route: CitizenPacketRoute) {
                repeat(200) {
                    if (!route.searching) return
                    while (workers.isNotEmpty()) workers.removeFirst().run()
                    clock += 1_000_000
                    paper.performTicks(1)
                    owner.pulse()
                }
                error("Planner did not finish")
            }
            @Suppress("UNCHECKED_CAST")
            fun metrics() = owner.debug()["preparation_lifetime"] as Map<String, Any>
            try {
                val success = request()
                finish(success)
                success.failure shouldBe null
                (clock < 100_000_000) shouldBe true
                metrics()["ready"] shouldBe 1L
                metrics()["max_ms"] shouldBe clock / 1_000_000.0
                @Suppress("UNCHECKED_CAST")
                val buckets = metrics()["buckets_ms"] as Map<String, Long>
                buckets.values.sum() shouldBe 1L
                buckets["<=100"] shouldBe 1L
                val cancelled = request()
                cancelled.cancel(); owner.pulse()
                metrics()["cancelled"] shouldBe 1L
                val denied = request(false)
                finish(denied)
                denied.failure shouldBe "no-path"
                metrics()["ready"] shouldBe 1L
                metrics()["failed"] shouldBe 1L
            } finally { owner.clear(); work.close(); tasks.close() }
        }
    }
})
