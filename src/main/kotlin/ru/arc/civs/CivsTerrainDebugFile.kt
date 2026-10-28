package ru.arc.civs

import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import org.bukkit.Chunk
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded operator capture, detached on Main and written on the existing IO worker. */
internal class CivsTerrainDebugFile(private val root: Path, private val executor: Executor,
    private val clock: () -> Long = System::nanoTime) {
    private val writing = AtomicBoolean()
    private var lastAcceptedAt: Long? = null

    fun save(capture: () -> Map<String, Any?>): CompletableFuture<Path> {
        check(Bukkit.isPrimaryThread())
        val now = clock()
        if (writing.get()) return CompletableFuture.failedFuture(CivsRejection("terrain-file-busy"))
        if (lastAcceptedAt?.let { now - it < COOLDOWN_NANOS } == true)
            return CompletableFuture.failedFuture(CivsRejection("terrain-file-throttled"))
        writing.set(true)
        val snapshot = try { snapshot(capture()) }
        catch (failure: Throwable) {
            writing.set(false)
            return CompletableFuture.failedFuture(failure)
        }
        lastAcceptedAt = now
        val result = try {
            CompletableFuture.supplyAsync({
                check(!Bukkit.isPrimaryThread()) { "Terrain file IO must run off Main" }
                val bytes = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) throw CivsRejection("terrain-file-too-large")
                prepareDirectory()
                AtomicFileStore(root, FILE, MAX_BYTES.toLong(), { value: ByteArray -> value }, { it }).write(bytes)
                FILE
            }, executor)
        } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        return result.whenComplete { _, _ -> writing.set(false) }
    }

    private fun snapshot(capture: Map<String, Any?>): Any {
        (capture["error"] as? String)?.let { throw CivsRejection(it) }
        if (capture.keys.any { it !in FIELDS } || capture["source"] != "fresh-native-command" ||
            capture["focus_origin"] != "operator-request" ||
            listOf("city", "worker", "world").any { capture[it] !is UUID } ||
            capture["voxel_count"] != 891 || capture["unavailable_count"] != 0 ||
            capture["size"] != listOf(9, 11, 9) || (capture["voxels"] as? List<*>)?.size != 891 ||
            (capture["palette"] as? List<*>)?.size !in 1..891 ||
            capture["required_load_level"] != Chunk.LoadLevel.ENTITY_TICKING)
            throw CivsRejection("invalid-terrain-capture")
        val chunks = capture["chunks"] as? List<*> ?: throw CivsRejection("invalid-terrain-capture")
        if (chunks.size !in 1..4 || chunks.any { row -> (row as? Map<*, *>)?.let {
                it["loaded"] != true || it["load_level"] != Chunk.LoadLevel.ENTITY_TICKING
            } != false }) throw CivsRejection("invalid-terrain-capture")
        return detached(mapOf("schema" to 1, "state" to "captured") + capture)!!
    }

    /** Copies only capture value types; neither Bukkit objects nor caller-owned collections cross the boundary. */
    private fun detached(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Short, is Byte -> value
        is Double -> value.takeIf(Double::isFinite) ?: throw CivsRejection("invalid-terrain-capture")
        is Float -> value.takeIf(Float::isFinite) ?: throw CivsRejection("invalid-terrain-capture")
        is UUID -> value.toString()
        is Enum<*> -> value.name
        is CivPosition -> Collections.unmodifiableMap(mapOf("x" to value.x, "y" to value.y, "z" to value.z))
        is List<*> -> Collections.unmodifiableList(value.map(::detached))
        is Map<*, *> -> Collections.unmodifiableMap(value.entries.associate { (key, entry) ->
            (key as? String ?: throw CivsRejection("invalid-terrain-capture")) to detached(entry)
        })
        else -> throw CivsRejection("invalid-terrain-capture")
    }

    private fun prepareDirectory() {
        Files.createDirectories(root)
        var directory = root.toRealPath()
        for (name in listOf("debug", "terrain")) {
            directory = directory.resolve(name)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
                throw CivsRejection("terrain-file-unsafe-path")
        }
    }

    companion object {
        private val FILE = Path.of("debug", "terrain", "latest.json")
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val COOLDOWN_NANOS = 5_000_000_000L
        private val gson = GsonBuilder().serializeNulls().create()
        private val FIELDS = setOf("source", "city", "worker", "building", "captured_at_ms", "actual",
            "focus_origin", "capture_center", "world", "required_load_level", "chunks", "focus", "standing",
            "geometry_version", "mining_step", "bounds", "size", "voxel_coordinates", "voxel_fields",
            "collision_coordinates", "collision_fields", "palette", "voxels", "voxel_count", "unavailable_count",
            "capture_elapsed_nanos")
    }
}
