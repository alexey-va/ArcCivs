package ru.arc.civs

import com.google.gson.Gson
import org.bukkit.Bukkit
import ru.arc.persistence.AtomicFileStore
import java.io.Writer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One selected city becomes bounded immutable JSON on Main; the existing IO owner drains its write. */
internal class CivsSettlementDebugFile(private val root: Path, private val executor: Executor,
    private val clock: () -> Long = System::nanoTime) {
    private val writing = AtomicBoolean()
    private var lastAcceptedAt: Long? = null

    fun save(cityId: UUID, capture: () -> Settlement): CompletableFuture<Path> {
        check(Bukkit.isPrimaryThread())
        val now = clock()
        if (writing.get()) return CompletableFuture.failedFuture(CivsRejection("settlement-file-busy"))
        if (lastAcceptedAt?.let { now - it < COOLDOWN_NANOS } == true)
            return CompletableFuture.failedFuture(CivsRejection("settlement-file-throttled"))
        writing.set(true)
        val snapshot = try {
            val city = capture()
            if (city.id != cityId) throw CivsRejection("settlement-file-city-mismatch")
            // The canonical store has the same 256 KiB ceiling. Stop serialization before copying oversized input.
            BoundedJsonWriter().also { gson.toJson(city, it) }.toString()
        } catch (failure: Throwable) {
            writing.set(false)
            return CompletableFuture.failedFuture(failure)
        }
        lastAcceptedAt = now
        val result = try {
            CompletableFuture.supplyAsync({
                check(!Bukkit.isPrimaryThread()) { "Settlement file IO must run off Main" }
                val bytes = snapshot.toByteArray(Charsets.UTF_8)
                if (bytes.size > CivsStore.MAX_CITY_BYTES) throw CivsRejection("settlement-file-too-large")
                prepareDirectory()
                AtomicFileStore(root, FILE, CivsStore.MAX_CITY_BYTES, { value: ByteArray -> value }, { it }).write(bytes)
                FILE
            }, executor)
        } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        return result.whenComplete { _, _ -> writing.set(false) }.copy()
    }

    private fun prepareDirectory() {
        Files.createDirectories(root)
        var directory = root.toRealPath()
        for (name in listOf("debug", "settlement")) {
            directory = directory.resolve(name)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
                throw CivsRejection("settlement-file-unsafe-path")
        }
    }

    private class BoundedJsonWriter : Writer() {
        private val value = StringBuilder()
        private fun admit(length: Int) {
            if (length.toLong() > CivsStore.MAX_CITY_BYTES - value.length)
                throw CivsRejection("settlement-file-too-large")
        }
        override fun write(chars: CharArray, offset: Int, length: Int) {
            admit(length)
            value.append(chars, offset, length)
        }
        override fun write(text: String, offset: Int, length: Int) {
            admit(length)
            value.append(text, offset, offset + length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = value.toString()
    }

    companion object {
        private val FILE = Path.of("debug", "settlement", "latest.json")
        private const val COOLDOWN_NANOS = 5_000_000_000L
        private val gson = Gson()
    }
}
