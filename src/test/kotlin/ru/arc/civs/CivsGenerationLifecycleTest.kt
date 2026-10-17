package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CivsGenerationLifecycleTest : FunSpec({
    test("duplicate disable and superseded enable cannot publish globals or open configuration before the actual predecessor drain") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                listOf("ArcCore", "packetevents", "WorldEdit", "Citizens").forEach(paper::createSimplePlugin)
                val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
                val oldWorker = BoundedAsyncExecutor("civs-predecessor-test", 1, 1)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val oldDrain = oldWorker.closeAsync { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                val predecessor = ArcCivsPlugin::class.java.getDeclaredField("predecessor").apply { isAccessible = true }
                predecessor.set(plugin, oldDrain)
                val beforeScheduling = TestTaskScheduler().also(Tasks::install)
                try {
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    paper.server.pluginManager.enablePlugin(plugin)
                    paper.performTicks(2)
                    (Tasks.scheduler === beforeScheduling) shouldBe true
                    plugin.dataFolder.resolve("config.yml").exists() shouldBe false
                    paper.server.pluginManager.disablePlugin(plugin)
                    plugin.onDisable()
                    plugin.drained().isDone shouldBe false
                    paper.server.pluginManager.enablePlugin(plugin)
                    paper.performTicks(2)
                    (Tasks.scheduler === beforeScheduling) shouldBe true
                    plugin.dataFolder.resolve("config.yml").exists() shouldBe false
                    release.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    // Deliberately absent schematic service: the current generation prepares config,
                    // then fails closed. The superseded first generation must never prepare at all.
                    while (plugin.isEnabled && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
                    plugin.isEnabled shouldBe false
                    plugin.dataFolder.resolve("config.yml").isFile shouldBe true
                    plugin.drained().get(5, TimeUnit.SECONDS)
                } finally {
                    release.countDown()
                    paper.server.pluginManager.disablePlugin(plugin)
                    plugin.drained().get(5, TimeUnit.SECONDS)
                    oldWorker.closeAsync().get(5, TimeUnit.SECONDS)
                }
            }
        }
    }
})
