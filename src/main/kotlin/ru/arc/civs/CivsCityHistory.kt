package ru.arc.civs

import ru.arc.core.BoundedAsyncExecutor
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset.UTC
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level

/** Plugin-owned, best-effort history. Only the bounded worker touches files; no native state is retained. */
internal class CivsCityHistory(
    private val root: Path,
    private val warning: (String) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val executor: BoundedAsyncExecutor = BoundedAsyncExecutor("ArcCivs-history", 1, 1024, true),
    private val maximumBytes: Long = 5L * 1024 * 1024,
) {
    private data class Loss(var dropped: Long = 0, var failed: Long = 0)
    private val losses = linkedMapOf<UUID?, Loss>()
    private val dropped = AtomicLong()
    private val failed = AtomicLong()
    private val warnedAt = AtomicLong(Long.MIN_VALUE)
    private var maintained: LocalDate? = null // Worker-owned, like every filesystem operation below.
    private var closing: CompletableFuture<Unit>? = null

    init {
        require(maximumBytes > 0)
        maintenance()
    }

    fun record(level: Level, city: UUID?, line: String) {
        val at = clock.instant()
        val detached = line.take(MAX_LINE_CHARACTERS).map { if (it.isISOControl()) ' ' else it }.joinToString("")
        executor.submit {
            maintain(at.atZone(UTC).toLocalDate())
            flushGaps(at)
            try { append(city, at, level, detached) }
            catch (failure: Exception) { lost(city, false, "append", failure) }
        }.whenComplete { _, failure ->
            if (failure != null) lost(city, true, "admission", failure)
        }
    }

    /** A lightweight scheduled pulse also reaches dormant streams when no settlement produces events. */
    fun maintenance() {
        executor.submit { maintain(clock.instant().atZone(UTC).toLocalDate()); flushGaps(clock.instant()) }
            .whenComplete { _, failure -> if (failure != null) lost(null, true, "maintenance-admission", failure) }
    }

    /** Seal only after all producers, including failed drains, have finished their completion callbacks. */
    fun closeAfter(producers: CompletableFuture<*>): CompletableFuture<Unit> =
        producers.handle { _, _ -> Unit }.thenCompose { closeAsync() }

    @Synchronized
    fun closeAsync(): CompletableFuture<Unit> {
        closing?.let { return it.copy() }
        return executor.closeAsync {
            val at = clock.instant()
            maintain(at.atZone(UTC).toLocalDate(), force = true)
            flushGaps(at)
            val remaining = synchronized(losses) { losses.values.sumOf { it.dropped + it.failed } }
            if (remaining != 0L) report("final-drain", null, force = true)
        }.also { closing = it }.copy()
    }

    private fun lost(city: UUID?, overflow: Boolean, stage: String, failure: Throwable) {
        synchronized(losses) {
            val loss = losses.getOrPut(city) { Loss() }
            if (overflow) { loss.dropped++; dropped.incrementAndGet() }
            else { loss.failed++; failed.incrementAndGet() }
        }
        report(stage, failure, city)
    }

    private fun problem(stage: String, failure: Throwable) {
        failed.incrementAndGet()
        report(stage, failure)
    }

    private fun report(stage: String, failure: Throwable?, city: UUID? = null, force: Boolean = false) {
        val now = System.nanoTime()
        val previous = warnedAt.get()
        if (!force && previous != Long.MIN_VALUE && now - previous < 60_000_000_000L) return
        if (!force && !warnedAt.compareAndSet(previous, now)) return
        if (force) warnedAt.set(now)
        warning(CivsDiagnostics.line("city_history_gap", "city" to city, "stage" to stage,
            "dropped_total" to dropped.get(), "io_failures_total" to failed.get(),
            "cause" to failure?.let { CivsDiagnostics.cause(it)?.javaClass?.simpleName }))
    }

    private fun flushGaps(at: Instant) {
        val pending = synchronized(losses) { losses.map { (city, loss) -> Triple(city, loss.dropped, loss.failed) } }
        for ((city, overflow, io) in pending) {
            try {
                append(city, at, Level.WARNING, CivsDiagnostics.line("city_history_gap", "city" to city,
                    "dropped" to overflow, "io_failures" to io))
                synchronized(losses) {
                    losses[city]?.let { loss ->
                        loss.dropped -= overflow
                        loss.failed -= io
                        if (loss.dropped == 0L && loss.failed == 0L) losses.remove(city)
                    }
                }
            } catch (failure: Exception) { problem("gap-write", failure) }
        }
    }

    private fun append(city: UUID?, at: Instant, level: Level, line: String) {
        directory(root)
        val stream = if (city == null) root.resolve("system") else {
            directory(root.resolve("settlements"))
            root.resolve("settlements").resolve(city.toString())
        }
        directory(stream)
        val latest = stream.resolve("latest.log")
        val day = at.atZone(UTC).toLocalDate()
        val bytes = "$at ${level.name} $line\n".toByteArray(Charsets.UTF_8)
        if (Files.exists(latest, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(latest, NOFOLLOW_LINKS)) { "History latest is not a regular file" }
            val size = Files.size(latest)
            val date = date(latest)
            if (date != day || size > 0 && size + bytes.size > maximumBytes) rotate(stream, latest, date, day)
        }
        Files.write(latest, bytes, CREATE, APPEND, NOFOLLOW_LINKS)
        Files.setLastModifiedTime(latest, FileTime.from(at))
    }

    private fun maintain(today: LocalDate, force: Boolean = false) {
        if (!force && maintained == today) return
        try {
            if (!Files.exists(root, NOFOLLOW_LINKS)) { maintained = today; return }
            require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "History root is not a directory" }
            val streams = mutableListOf<Path>()
            root.resolve("system").takeIf { Files.isDirectory(it, NOFOLLOW_LINKS) }?.let(streams::add)
            val settlements = root.resolve("settlements")
            if (Files.isDirectory(settlements, NOFOLLOW_LINKS)) Files.newDirectoryStream(settlements).use { entries ->
                entries.filterTo(streams) { stream -> Files.isDirectory(stream, NOFOLLOW_LINKS) &&
                    runCatching { UUID.fromString(stream.fileName.toString()).toString() == stream.fileName.toString() }.getOrDefault(false) }
            }
            streams.forEach { stream ->
                try {
                    val latest = stream.resolve("latest.log")
                    if (Files.isRegularFile(latest, NOFOLLOW_LINKS) && (date(latest) != today || Files.size(latest) >= maximumBytes))
                        rotate(stream, latest, date(latest), today)
                    else prune(stream, today)
                } catch (failure: Exception) { problem("stream-maintenance", failure) }
            }
            maintained = today
        } catch (failure: Exception) { problem("maintenance", failure) }
    }

    private fun rotate(stream: Path, latest: Path, date: LocalDate, today: LocalDate) {
        // Retention deletes low numbers; filling those gaps would immediately prune the newest record.
        var index = Files.newDirectoryStream(stream).use { entries -> entries.mapNotNull { path ->
            val match = ARCHIVE.matchEntire(path.fileName.toString()) ?: return@mapNotNull null
            if (match.groupValues[1] == date.toString()) match.groupValues[2].toLongOrNull() else null
        }.maxOrNull() ?: 0L }
        while (true) {
            check(index < Long.MAX_VALUE) { "History archive sequence exhausted" }
            index++
            try { Files.move(latest, stream.resolve("$date.$index.log")); break }
            catch (_: FileAlreadyExistsException) { /* Keep every existing archive, including after restart. */ }
        }
        prune(stream, today)
    }

    private fun prune(stream: Path, today: LocalDate) {
        val archives = Files.newDirectoryStream(stream).use { entries -> entries.mapNotNull { path ->
            val match = ARCHIVE.matchEntire(path.fileName.toString()) ?: return@mapNotNull null
            if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return@mapNotNull null
            val day = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            val sequence = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            Triple(path, day, sequence)
        }.sortedWith(compareByDescending<Triple<Path, LocalDate, Long>> { it.second }.thenByDescending { it.third }) }
        archives.forEachIndexed { index, (path, day, _) ->
            if (index >= 10 || day.isBefore(today.minusDays(30))) Files.delete(path)
        }
    }

    private fun directory(path: Path) {
        try { Files.createDirectory(path) } catch (_: FileAlreadyExistsException) {
            require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "History stream is not a directory" }
        }
    }

    private fun date(path: Path): LocalDate = Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toInstant().atZone(UTC).toLocalDate()

    private companion object {
        const val MAX_LINE_CHARACTERS = 16_384
        val ARCHIVE = Regex("(\\d{4}-\\d{2}-\\d{2})\\.([1-9]\\d*)\\.log")
    }
}
