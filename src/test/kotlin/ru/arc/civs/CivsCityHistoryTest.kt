package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ru.arc.core.BoundedAsyncExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class CivsCityHistoryTest : FunSpec({
    test("typed city events stay out of global INFO and warnings have one global and one city copy") {
        val folder = Files.createTempDirectory("civs-history-routing")
        val records = CopyOnWriteArrayList<LogRecord>()
        val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        val history = CivsCityHistory(folder.resolve("logs"), logger::warning)
        val city = UUID.randomUUID()
        try {
            CivsDiagnostics.install(history, logger)
            CivsDiagnostics.event("city_info", "city" to city, "city_name" to "../../<red>Oak\nTown")
            val cause = IllegalArgumentException("distinct lower cause").apply {
                stackTrace = arrayOf(StackTraceElement("ResidentWorker", "deliver", "ResidentWorker.kt", 147))
            }
            val failure = IllegalStateException("distinct outer failure", cause)
            CivsDiagnostics.record(logger, Level.WARNING, city, CivsDiagnostics.line("city_warning", "city" to city), failure)
            val debug = CivsDebug.lines(listOf("citizens", city.toString())) { _, _, _ -> mapOf("workers" to 1) }.single()
            CivsDiagnostics.event("cityless_info", "city" to "../../not-a-uuid")
            history.closeAsync().get(5, SECONDS)
            records.count { it.level == Level.INFO } shouldBe 0
            records.count { it.level == Level.WARNING } shouldBe 1
            records.single { it.level == Level.WARNING }.thrown shouldBe failure
            val cityLines = Files.readAllLines(folder.resolve("logs/settlements/$city/latest.log"))
            cityLines.size shouldBe 3
            cityLines.count { "event=city_warning" in it } shouldBe 1
            val warning = cityLines.single { "event=city_warning" in it }
            warning shouldContain "java.lang.IllegalStateException"
            warning shouldContain "java.lang.IllegalArgumentException"
            warning.contains("distinct outer failure") shouldBe false
            warning.contains("distinct lower cause") shouldBe false
            warning shouldContain "ResidentWorker.kt:147"
            cityLines.count { it.endsWith(debug) } shouldBe 1
            cityLines.all { it.none(Char::isISOControl) } shouldBe true
            Files.readString(folder.resolve("logs/system/latest.log")) shouldContain "event=cityless_info"
            Files.list(folder.resolve("logs/settlements")).use { it.count() } shouldBe 1L
        } finally {
            history.closeAsync().get(5, SECONDS)
            CivsDiagnostics.detach(history)
            logger.removeHandler(handler)
            folder.toFile().deleteRecursively()
        }
    }

    test("restart appends UTF8 UTC records to the existing latest file") {
        val folder = Files.createTempDirectory("civs-history-restart")
        val at = Instant.parse("2026-10-25T08:30:00Z")
        val city = UUID.randomUUID()
        try {
            repeat(2) { index ->
                val history = CivsCityHistory(folder.resolve("logs"), {}, Clock.fixed(at, UTC))
                try { history.record(Level.INFO, city, "ARC_CIVS event=append sequence=$index name=Берёзки") }
                finally { history.closeAsync().get(5, SECONDS) }
            }
            val lines = Files.readAllLines(folder.resolve("logs/settlements/$city/latest.log"))
            lines.size shouldBe 2
            lines.first() shouldContain "2026-10-25T08:30:00Z INFO ARC_CIVS event=append sequence=0 name=Берёзки"
            lines.last() shouldContain "sequence=1"
        } finally { folder.toFile().deleteRecursively() }
    }

    test("UTC day rotation preserves colliding archives and size rotation keeps every retained record") {
        val folder = Files.createTempDirectory("civs-history-rotation")
        val city = UUID.randomUUID()
        val stream = folder.resolve("logs/settlements/$city")
        val clock = HistoryClock(Instant.parse("2026-10-24T23:59:59Z"))
        val executor = BoundedAsyncExecutor("ArcCivs-history-rotation-test", 1, 1024, true)
        val history = CivsCityHistory(folder.resolve("logs"), {}, clock, executor, 512)
        try {
            history.record(Level.INFO, city, "ARC_CIVS event=yesterday")
            executor.submit { Unit }.get(5, SECONDS)
            Files.writeString(stream.resolve("2026-10-24.1.log"), "existing archive\n")
            clock.at = Instant.parse("2026-10-25T00:00:00Z")
            repeat(8) { index -> history.record(Level.INFO, city, "ARC_CIVS event=sized index=$index value=${"x".repeat(140)}") }
            history.closeAsync().get(5, SECONDS)
            Files.readString(stream.resolve("2026-10-24.1.log")) shouldBe "existing archive\n"
            Files.readString(stream.resolve("2026-10-24.2.log")) shouldContain "event=yesterday"
            val files = Files.list(stream).use { it.toList() }
            (files.count { it.fileName.toString().startsWith("2026-10-25.") } > 0) shouldBe true
            val contents = files.joinToString("\n") { Files.readString(it) }
            repeat(8) { index -> contents.split("index=$index ").size shouldBe 2 }
            files.filter { it.fileName.toString().startsWith("2026-10-25.") || it.fileName.toString() == "latest.log" }
                .all { Files.size(it) <= 512L } shouldBe true
        } finally { history.closeAsync().get(5, SECONDS); folder.toFile().deleteRecursively() }
    }

    test("same-day size rotation retains the newest records after archive retention creates gaps") {
        val folder = Files.createTempDirectory("civs-history-retention-sequence")
        val city = UUID.randomUUID()
        val stream = folder.resolve("logs/settlements/$city")
        val history = CivsCityHistory(folder.resolve("logs"), {},
            Clock.fixed(Instant.parse("2026-10-25T12:00:00Z"), UTC),
            BoundedAsyncExecutor("ArcCivs-history-sequence-test", 1, 1024, true), 256)
        try {
            repeat(17) { index -> history.record(Level.INFO, city,
                "ARC_CIVS event=retention sequence=$index value=${"x".repeat(160)}") }
            history.closeAsync().get(5, SECONDS)
            val files = Files.list(stream).use { it.toList() }
            files.size shouldBe 11
            val records = files.flatMap(Files::readAllLines)
            records.size shouldBe 11
            repeat(17) { index -> records.count { "sequence=$index " in it } shouldBe if (index >= 6) 1 else 0 }
            Files.readString(stream.resolve("latest.log")) shouldContain "sequence=16 "
        } finally { history.closeAsync().get(5, SECONDS); folder.toFile().deleteRecursively() }
    }

    test("startup and daily maintenance prune dormant owned streams including an expired latest") {
        val folder = Files.createTempDirectory("civs-history-retention")
        val stream = Files.createDirectories(folder.resolve("logs/settlements/${UUID.randomUUID()}"))
        val unrelated = Files.createDirectories(folder.resolve("logs/settlements/not-a-city"))
        val today = Instant.parse("2026-10-25T12:00:00Z")
        Files.writeString(stream.resolve("latest.log"), "expired latest")
        Files.setLastModifiedTime(stream.resolve("latest.log"), FileTime.from(Instant.parse("2026-08-01T00:00:00Z")))
        Files.writeString(stream.resolve("2026-08-01.1.log"), "expired archive")
        repeat(14) { index -> Files.writeString(stream.resolve("2026-10-24.${index + 1}.log"), "archive $index") }
        Files.writeString(stream.resolve("operator-notes.txt"), "untouched")
        Files.writeString(unrelated.resolve("2026-08-01.1.log"), "unowned")
        val clock = HistoryClock(today)
        val executor = BoundedAsyncExecutor("ArcCivs-history-retention-test", 1, 1024, true)
        val history = CivsCityHistory(folder.resolve("logs"), {}, clock, executor)
        try {
            executor.submit { Unit }.get(5, SECONDS)
            Files.exists(stream.resolve("latest.log")) shouldBe false
            Files.exists(stream.resolve("2026-08-01.1.log")) shouldBe false
            Files.list(stream).use { paths -> paths.filter { it.fileName.toString().endsWith(".log") }.count() } shouldBe 10L
            Files.readString(stream.resolve("operator-notes.txt")) shouldBe "untouched"
            Files.readString(unrelated.resolve("2026-08-01.1.log")) shouldBe "unowned"
            clock.at = Instant.parse("2026-12-01T00:00:00Z")
            history.maintenance()
            executor.submit { Unit }.get(5, SECONDS)
            Files.list(stream).use { paths -> paths.filter { it.fileName.toString().endsWith(".log") }.count() } shouldBe 0L
        } finally { history.closeAsync().get(5, SECONDS); folder.toFile().deleteRecursively() }
    }

    test("full queue never writes on the caller and the final worker drain records dropped events") {
        val folder = Files.createTempDirectory("civs-history-overflow")
        val warnings = CopyOnWriteArrayList<String>()
        val executor = BoundedAsyncExecutor("ArcCivs-history-blocked-test", 1, 1, true)
        val history = CivsCityHistory(folder.resolve("logs"), warnings::add, executor = executor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val city = UUID.randomUUID()
        try {
            executor.submit { Unit }.get(5, SECONDS)
            executor.submit { entered.countDown(); release.await(5, SECONDS) }
            entered.await(5, SECONDS) shouldBe true
            history.record(Level.INFO, city, "ARC_CIVS event=accepted")
            history.record(Level.INFO, city, "ARC_CIVS event=overflow")
            Files.exists(folder.resolve("logs")) shouldBe false
            warnings.size shouldBe 1
            warnings.single() shouldContain "dropped_total=1"
            warnings.single() shouldContain "city=$city"
            val closed = history.closeAsync()
            closed.isDone shouldBe false
            release.countDown()
            closed.get(5, SECONDS)
            val text = Files.readString(folder.resolve("logs/settlements/$city/latest.log"))
            text shouldContain "event=accepted"
            text shouldContain "event=city_history_gap"
            text shouldContain "dropped=1"
            ("event=overflow" in text) shouldBe false
        } finally { release.countDown(); history.closeAsync().get(5, SECONDS); folder.toFile().deleteRecursively() }
    }

    test("an IO failure stays visible and a recovered stream receives its gap record") {
        val folder = Files.createTempDirectory("civs-history-io")
        val root = folder.resolve("logs")
        Files.writeString(root, "blocks the directory")
        val warnings = CopyOnWriteArrayList<String>()
        val executor = BoundedAsyncExecutor("ArcCivs-history-io-test", 1, 1024, true)
        val history = CivsCityHistory(root, warnings::add, executor = executor)
        val city = UUID.randomUUID()
        try {
            history.record(Level.INFO, city, "ARC_CIVS event=failed_write")
            executor.submit { Unit }.get(5, SECONDS)
            warnings.isNotEmpty() shouldBe true
            Files.delete(root)
            history.record(Level.INFO, city, "ARC_CIVS event=recovered_write")
            history.closeAsync().get(5, SECONDS)
            val text = Files.readString(root.resolve("settlements/$city/latest.log"))
            text shouldContain "event=recovered_write"
            text shouldContain "event=city_history_gap"
            text shouldContain "io_failures=1"
        } finally { history.closeAsync().get(5, SECONDS); folder.toFile().deleteRecursively() }
    }

    test("shutdown keeps admission open for late producer completions and closes even when their drain fails") {
        val folder = Files.createTempDirectory("civs-history-late-producer")
        val history = CivsCityHistory(folder.resolve("logs"), {})
        val producers = CompletableFuture<Unit>()
        try {
            val close = history.closeAfter(producers)
            close.isDone shouldBe false
            history.record(Level.INFO, null, "ARC_CIVS event=late_completion")
            producers.completeExceptionally(IllegalStateException("producer failed"))
            close.get(5, SECONDS)
            Files.readString(folder.resolve("logs/system/latest.log")) shouldContain "event=late_completion"
        } finally { producers.complete(Unit); history.closeAsync().get(5, SECONDS); folder.toFile().deleteRecursively() }
    }
})

private class HistoryClock(@Volatile var at: Instant) : Clock() {
    override fun instant(): Instant = at
    override fun getZone(): ZoneId = UTC
    override fun withZone(zone: ZoneId): Clock = this
}
