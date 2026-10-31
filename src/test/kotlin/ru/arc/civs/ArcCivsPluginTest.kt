package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.core.TestTaskScheduler
import ru.arc.network.BackendServerId
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.runtime.PaperPluginRuntimeState
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord

class ArcCivsPluginTest : FunSpec({
    test("bounded village planning cannot starve store IO and shutdown fences callbacks while draining both lanes") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
            val lifecycle = PaperPluginRuntime(plugin, "planning-drain-test", TestTaskScheduler()).apply { start() }
            plugin.lifecycleField("runtime", lifecycle)
            val root = Files.createTempDirectory("arc-civs-planning-drain-")
            val native = CompletableFuture<Unit>()
            val terminal = CompletableFuture<Unit>()
            val networkCloses = AtomicInteger()
            val historyCloses = AtomicInteger()
            lifecycle.own(AutoCloseable {
                plugin.lifecycleField("historyClose", plugin.lifecycleFuture("nativeClose").thenApply {
                    historyCloses.incrementAndGet()
                    Unit
                })
            })
            val (storage, planning) = plugin.ownExecutors(lifecycle, listOf(native), terminal) {
                networkCloses.incrementAndGet()
                CompletableFuture.completedFuture(Unit)
            }
            val store = lifecycle.own(CivsStore(root, storage))
            val token = lifecycle.tasks.token()
            val serviceFenced = AtomicBoolean()
            lifecycle.own(AutoCloseable {
                lifecycle.tasks.isCurrent(token) shouldBe false
                serviceFenced.set(true)
            })
            val entered = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val queuedCalls = AtomicInteger()
            val lateWrites = AtomicInteger()
            val lateCallback = CompletableFuture<Unit>()
            val city = executorDrainCity()
            try {
                val held = planning.submit {
                    entered.countDown()
                    check(unblock.await(10, TimeUnit.SECONDS))
                    serviceFenced.get() shouldBe true
                    lifecycle.tasks.callSync(token) {
                        lateWrites.incrementAndGet()
                        store.save(city.copy(revision = 2))
                        Unit
                    }.whenComplete { _, failure ->
                        if (failure == null) lateCallback.complete(Unit) else lateCallback.completeExceptionally(failure)
                    }
                    Thread.currentThread().name
                }
                entered.await(5, TimeUnit.SECONDS) shouldBe true
                val queued = List(16) { planning.submit { queuedCalls.incrementAndGet() } }
                planning.submit { queuedCalls.incrementAndGet() }.isCompletedExceptionally shouldBe true
                queuedCalls.get() shouldBe 0

                storage.submit { Thread.currentThread().name }.get(5, TimeUnit.SECONDS) shouldBe "ArcCivs-storage-1"
                store.save(city).get(5, TimeUnit.SECONDS) shouldBe city
                store.load().get(5, TimeUnit.SECONDS) shouldBe listOf(city)
                held.isDone shouldBe false
                val acceptedWrite = store.save(city.copy(revision = 1))

                plugin.prepareHotload().let { status ->
                    status["state"] shouldBe "preparing"
                    (status["storage"] as Map<*, *>)["state"] shouldBe "complete"
                    (status["planning"] as Map<*, *>)["state"] shouldBe "pending"
                    (status["native"] as Map<*, *>)["state"] shouldBe "pending"
                    (status["history"] as Map<*, *>)["state"] shouldBe "pending"
                }
                acceptedWrite.get(5, TimeUnit.SECONDS) shouldBe city.copy(revision = 1)
                lifecycle.state shouldBe PaperPluginRuntimeState.CLOSED
                serviceFenced.get() shouldBe true
                storage.submit { error("closed storage ran work") }.isCompletedExceptionally shouldBe true
                planning.submit { error("closed planner ran work") }.isCompletedExceptionally shouldBe true
                networkCloses.get() shouldBe 1
                historyCloses.get() shouldBe 0
                terminal.isDone shouldBe false
                native.complete(Unit)
                plugin.drained().isDone shouldBe false
                (plugin.hotloadStatus()["native"] as Map<*, *>)["state"] shouldBe "complete"
                (plugin.hotloadStatus()["planning"] as Map<*, *>)["state"] shouldBe "pending"

                unblock.countDown()
                held.get(5, TimeUnit.SECONDS) shouldBe "ArcCivs-planning-1"
                queued.forEach { it.get(5, TimeUnit.SECONDS) }
                plugin.drained().get(5, TimeUnit.SECONDS) shouldBe Unit
                lateCallback.isCompletedExceptionally shouldBe true
                lateWrites.get() shouldBe 0
                queuedCalls.get() shouldBe 16
                terminal.get(5, TimeUnit.SECONDS) shouldBe Unit
                historyCloses.get() shouldBe 1
                plugin.prepareHotload()["state"] shouldBe "ready"
                networkCloses.get() shouldBe 1
                CivsStore(root, Executor(Runnable::run)).use { reopened ->
                    reopened.load().join() shouldBe listOf(city.copy(revision = 1))
                }
            } finally {
                unblock.countDown()
                native.complete(Unit)
                lifecycle.close()
                planning.closeAsync().get(5, TimeUnit.SECONDS)
                storage.closeAsync().get(5, TimeUnit.SECONDS)
                root.toFile().deleteRecursively()
            }
        }
    }

    test("hotload prepare closes once and returns while native retirement and city history are pending") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
            val lifecycle = PaperPluginRuntime(plugin, "hotload-test", TestTaskScheduler()).apply { start() }
            val native = CompletableFuture<Unit>()
            val history = CompletableFuture<Unit>()
            var closes = 0
            lifecycle.own(AutoCloseable {
                closes++
                plugin.lifecycleField("nativeRetirementClose", native)
                plugin.lifecycleField("nativeClose", native)
                plugin.lifecycleField("historyClose", native.handle { _, _ -> Unit }.thenCompose { history })
            })
            plugin.lifecycleField("runtime", lifecycle)

            plugin.hotloadStatus().let { status ->
                status["schema"] shouldBe 1
                status["version"] shouldBe plugin.pluginMeta.version
                status["state"] shouldBe "active"
            }
            plugin.prepareHotload()["state"] shouldBe "preparing"
            native.isDone shouldBe false
            lifecycle.state shouldBe PaperPluginRuntimeState.CLOSED
            plugin.drained().isDone shouldBe false
            plugin.prepareHotload()["state"] shouldBe "preparing"
            closes shouldBe 1

            native.complete(Unit)
            plugin.hotloadStatus().let { status ->
                status["state"] shouldBe "preparing"
                (status["storage"] as Map<*, *>)["state"] shouldBe "complete"
                (status["native"] as Map<*, *>)["state"] shouldBe "complete"
                (status["history"] as Map<*, *>)["state"] shouldBe "pending"
            }
            plugin.drained().isDone shouldBe false
            history.complete(Unit)
            plugin.drained().join() shouldBe Unit
            plugin.prepareHotload()["state"] shouldBe "ready"
            closes shouldBe 1
        }
    }

    for (phase in listOf("planning", "native", "history")) {
        test("hotload preserves a failed $phase drain and refuses another enable on the same instance") {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
                val lifecycle = PaperPluginRuntime(plugin, "hotload-test", TestTaskScheduler()).apply { start() }
                val planning = CompletableFuture<Unit>()
                val native = CompletableFuture<Unit>()
                val history = CompletableFuture<Unit>()
                lifecycle.own(AutoCloseable {
                    val workers = CompletableFuture.allOf(planning, native).thenApply { Unit }
                    plugin.lifecycleField("planningClose", planning)
                    plugin.lifecycleField("nativeRetirementClose", native)
                    plugin.lifecycleField("nativeClose", workers)
                    plugin.lifecycleField("historyClose", workers.handle { _, _ -> Unit }.thenCompose { history })
                })
                plugin.lifecycleField("runtime", lifecycle)
                plugin.prepareHotload()["state"] shouldBe "preparing"
                if (phase == "planning") planning.completeExceptionally(IllegalStateException("planning drain failed"))
                else planning.complete(Unit)
                if (phase == "native") native.completeExceptionally(CancellationException("native provider stopped"))
                else native.complete(Unit)
                if (phase == "history") history.completeExceptionally(IllegalStateException("history writer failed"))
                else history.complete(Unit)

                plugin.hotloadStatus().let { status ->
                    status["state"] shouldBe "failed"
                    (status["storage"] as Map<*, *>)["state"] shouldBe "complete"
                    (status[phase] as Map<*, *>)["state"] shouldBe "failed"
                    status["failure"] shouldBe if (phase == "native") "CancellationException" else "IllegalStateException"
                }
                plugin.drained().isCompletedExceptionally shouldBe true
                val messages = mutableListOf<String>()
                val handler = object : Handler() {
                    override fun publish(record: LogRecord) { messages += record.message }
                    override fun flush() = Unit
                    override fun close() = Unit
                }
                plugin.logger.addHandler(handler)
                try { plugin.onEnable() } finally { plugin.logger.removeHandler(handler) }
                messages shouldContain "ArcCivs startup refused: previous cleanup failed"
                plugin.dataFolder.resolve("config.yml").exists() shouldBe false
                plugin.hotloadStatus()["state"] shouldBe "failed"
            }
        }
    }

    test("enable refuses a pending predecessor and disable clears command references without replacing its drain") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
            val lifecycle = PaperPluginRuntime(plugin, "hotload-test", TestTaskScheduler()).apply { start() }
            val native = CompletableFuture<Unit>()
            var closes = 0
            lifecycle.own(AutoCloseable {
                closes++
                plugin.lifecycleField("nativeRetirementClose", native)
                plugin.lifecycleField("nativeClose", native)
            })
            plugin.lifecycleField("runtime", lifecycle)
            val router = CivsCommand(mockk(), mockk(), mockk(), mockk())
            val command = requireNotNull(plugin.getCommand("civs")).apply { setExecutor(router); tabCompleter = router }
            plugin.prepareHotload()
            val messages = mutableListOf<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { messages += record.message }
                override fun flush() = Unit
                override fun close() = Unit
            }
            plugin.logger.addHandler(handler)
            try { plugin.onEnable() } finally { plugin.logger.removeHandler(handler) }
            messages shouldContain "ArcCivs startup refused: previous cleanup is still pending; retry enable after it finishes"
            plugin.onDisable()
            closes shouldBe 1
            (command.executor === router) shouldBe false
            command.tabCompleter shouldBe null
            plugin.drained().isDone shouldBe false
            native.complete(Unit)
            plugin.hotloadStatus()["state"] shouldBe "ready"
        }
    }

    test("network activation requires explicit backend identity and shared SQL config") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
            val config = YamlConfiguration()
            shouldThrow<IllegalArgumentException> { plugin.networkServer(config) }.message shouldContain "explicit server-id"
            config.set("server-id", "spawn")
            shouldThrow<IllegalArgumentException> { plugin.networkServer(config) }.message shouldContain "mysql.host"
            config.set("mysql.host", "database.example")
            config.set("mysql.database", "towns")
            config.set("mysql.username", "towns")
            config.set("mysql.password", "")
            plugin.networkServer(config) shouldBe BackendServerId.of("spawn")
            config.set("server-id", "spawn;stop")
            shouldThrow<IllegalArgumentException> { plugin.networkServer(config) }
        }
    }

    for (dependency in listOf("ArcCore", "packetevents", "WorldEdit")) {
        test("startup stops before configuration or services when $dependency is present but disabled") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val dependencies = listOf("ArcCore", "packetevents", "WorldEdit")
                        .associateWith(paper::createSimplePlugin)
                    paper.server.pluginManager.disablePlugin(dependencies.getValue(dependency))
                    val plugin = paper.server.pluginManager.loadPlugin(ArcCivsPlugin::class.java) as ArcCivsPlugin
                    val messages = mutableListOf<String>()
                    val handler = object : Handler() {
                        override fun publish(record: LogRecord) { messages += record.message }
                        override fun flush() = Unit
                        override fun close() = Unit
                    }
                    plugin.logger.addHandler(handler)
                    try {
                        paper.server.pluginManager.enablePlugin(plugin)
                        plugin.isEnabled shouldBe false
                        plugin.dataFolder.resolve("config.yml").exists() shouldBe false
                        plugin.dataFolder.resolve("settlements").exists() shouldBe false
                        paper.server.scheduler.pendingTasks.any { it.owner == plugin } shouldBe false
                        messages shouldContain "ArcCivs startup refused: mandatory dependencies are not enabled: $dependency"
                    } finally {
                        plugin.logger.removeHandler(handler)
                    }
                }
            }
        }
    }
})

private fun ArcCivsPlugin.lifecycleField(name: String, value: Any) {
    ArcCivsPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
}

@Suppress("UNCHECKED_CAST")
private fun ArcCivsPlugin.lifecycleFuture(name: String): CompletableFuture<Unit> =
    ArcCivsPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as CompletableFuture<Unit>

private fun executorDrainCity(): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0,
        "minecolonies/nordic/fundamentals/townhall1", CivBox(CivPosition(-2, 60, -2), CivPosition(2, 68, 2)),
        CivPosition(0, 61, -2), BuildingPhase.CONSTRUCTING)
    return CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Drain Town", UUID.randomUUID(),
        CivPosition(0, 64, 0), 16, "nordic", hall, 1_000)
}
