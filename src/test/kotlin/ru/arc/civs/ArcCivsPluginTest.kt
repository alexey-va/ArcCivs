package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.bukkit.configuration.file.YamlConfiguration
import ru.arc.network.BackendServerId
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.logging.Handler
import java.util.logging.LogRecord

class ArcCivsPluginTest : FunSpec({
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

    for (dependency in listOf("ArcCore", "packetevents", "WorldEdit", "Citizens")) {
        test("startup stops before configuration or services when $dependency is present but disabled") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val dependencies = listOf("ArcCore", "packetevents", "WorldEdit", "Citizens")
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
