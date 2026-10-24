package ru.arc.civs

import ru.arc.persistence.AtomicFileStore
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.util.TreeSet
import java.util.UUID
import java.util.zip.CRC32

/** Immutable compact membership shared with the owner thread. UUID identity survives moves between chunks/worlds. */
internal class CivConsumedItemIndex private constructor(private val buckets: Array<LongArray>, val size: Int) {
    val largestBucket: Int get() = buckets.maxOf { it.size / 2 }

    operator fun contains(id: UUID): Boolean {
        val values = buckets[prefix(id)]
        var low = 0
        var high = values.size / 2 - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val order = compare(values[middle * 2], values[middle * 2 + 1], id)
            if (order == 0) return true
            if (order < 0) low = middle + 1 else high = middle - 1
        }
        return false
    }

    fun adding(ids: Collection<UUID>): CivConsumedItemIndex {
        val added = ids.distinct().filterNot { it in this }
        if (added.isEmpty()) return this
        if (size.toLong() + added.size > MAX_ITEMS) throw CivsRejection("ground-history-full")
        val next = buckets.copyOf()
        for ((bucket, values) in added.groupBy(::prefix)) {
            val old = buckets[bucket]
            if (old.size / 2 + values.size > MAX_BUCKET_ITEMS) throw CivsRejection("ground-history-full")
            val ordered = TreeSet<UUID>()
            for (offset in old.indices step 2) ordered += UUID(old[offset], old[offset + 1])
            ordered.addAll(values)
            next[bucket] = LongArray(ordered.size * 2).also { packed ->
                ordered.forEachIndexed { i, id -> packed[i * 2] = id.mostSignificantBits; packed[i * 2 + 1] = id.leastSignificantBits }
            }
        }
        return CivConsumedItemIndex(next, size + added.size)
    }

    companion object {
        const val MAX_ITEMS = 1_048_576
        const val MAX_BUCKET_ITEMS = 8_192
        val EMPTY = CivConsumedItemIndex(Array(256) { LongArray(0) }, 0)
        fun prefix(id: UUID): Int = (id.mostSignificantBits ushr 56).toInt() and 255
        private fun compare(high: Long, low: Long, id: UUID): Int = high.compareTo(id.mostSignificantBits)
            .takeIf { it != 0 } ?: low.compareTo(id.leastSignificantBits)
    }
}

/** Local receipt companion, called only under CivsStore's IO lock/executor; never expires or evicts UUIDs. */
internal class CivsConsumedItems {
    private data class State(val index: CivConsumedItemIndex, val worlds: Set<UUID>)
    private var state: State? = null

    fun load(root: Path): CivConsumedItemIndex {
        state = null
        val directory = root.resolve(DIRECTORY)
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return CivConsumedItemIndex.EMPTY.also { state = State(it, emptySet()) }
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Ground history directory is not a real directory" }
        var index = CivConsumedItemIndex.EMPTY
        var bytes = 0L
        val worlds = linkedSetOf<UUID>()
        Files.newDirectoryStream(directory).use { entries ->
            for (worldPath in entries) {
                require(worlds.size < MAX_WORLDS && Files.isDirectory(worldPath, NOFOLLOW_LINKS)) { "Invalid or excessive ground history worlds" }
                val world = UUID.fromString(worldPath.fileName.toString())
                require(worldPath.fileName.toString() == world.toString() && worlds.add(world)) { "Invalid ground history world name" }
                var files = 0
                Files.newDirectoryStream(worldPath).use { buckets ->
                    for (file in buckets) {
                        require(++files <= 512 && Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Invalid or excessive ground history files" }
                        val length = Files.size(file)
                        require(length in 0..MAX_BYTES.toLong()) { "Oversized ground history bucket" }
                        bytes += length
                        require(bytes <= MAX_DISK_BYTES) { "Ground history exceeds its total byte limit" }
                        val name = file.fileName.toString()
                        if (TEMP_FILE.matches(name)) continue // A bounded orphan from a crash before AtomicFileStore's rename.
                        require(BUCKET_FILE.matches(name)) { "Unknown ground history file" }
                        val prefix = name.substringBefore('.').toInt(16)
                        val ids = requireNotNull(bucketStore(root, world, prefix).loadOrNull())
                        require(ids.none { it in index }) { "Duplicate consumed UUID across ground history buckets" }
                        index = index.adding(ids)
                    }
                }
            }
        }
        state = State(index, worlds)
        return index
    }

    /** Pending receipts reserve history capacity before any native debit, including per-prefix/world limits. */
    fun admit(root: Path, transaction: CivInventoryTransaction, pending: List<CivInventoryTransaction>) {
        val ids = transaction.collected?.ground.orEmpty().map(CivGroundItemChange::entityId)
        if (ids.isEmpty()) return
        val current = ensure(root)
        if (ids.any { it in current.index }) throw CivsRejection("ground-item-consumed")
        val reservations = pending.filter { it.collected?.ground?.isNotEmpty() == true }
        if ((current.worlds + reservations.map { it.after.worldId } + transaction.after.worldId).size > MAX_WORLDS)
            throw CivsRejection("ground-history-full")
        current.index.adding(reservations.flatMap { it.collected!!.ground.map(CivGroundItemChange::entityId) } + ids)
    }

    /** After the native debit ACK, before city credit. Partial file success remains covered by the original receipt. */
    fun mark(root: Path, world: UUID, ids: Collection<UUID>): CivConsumedItemIndex {
        val current = ensure(root)
        val added = ids.distinct().filterNot { it in current.index }
        try {
            if (added.isEmpty()) {
                // A reopened marker may follow a rename with an uncertain directory force. Re-acknowledge
                // the bounded directory tree before treating an existing UUID as a durable credit fence.
                if (ids.isNotEmpty()) forceDirectories(root, current.worlds)
                return current.index
            }
            val next = current.index.adding(added)
            if (world !in current.worlds && current.worlds.size >= MAX_WORLDS) throw CivsRejection("ground-history-full")
            val directory = root.resolve(DIRECTORY)
            require(!Files.exists(directory, NOFOLLOW_LINKS) || Files.isDirectory(directory, NOFOLLOW_LINKS))
            Files.createDirectories(directory)
            FileChannel.open(root, READ).use { it.force(true) }
            FileChannel.open(requireNotNull(root.parent), READ).use { it.force(true) }
            val worldPath = directory.resolve(world.toString())
            require(!Files.exists(worldPath, NOFOLLOW_LINKS) || Files.isDirectory(worldPath, NOFOLLOW_LINKS))
            Files.createDirectories(worldPath)
            FileChannel.open(directory, READ).use { it.force(true) }
            for ((prefix, additions) in added.groupBy { CivConsumedItemIndex.prefix(it) }) {
                val store = bucketStore(root, world, prefix)
                store.write((store.loadOrNull().orEmpty() + additions).distinct().sorted())
            }
            state = State(next, current.worlds + world)
            return next
        } catch (failure: Exception) {
            state = null // Unknown rename outcome must be read back before a retry or a new admission.
            throw failure
        }
    }

    private fun forceDirectories(root: Path, worlds: Set<UUID>) {
        val directory = root.resolve(DIRECTORY)
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Ground history directory is not a real directory" }
        for (world in worlds) {
            val worldPath = directory.resolve(world.toString())
            require(Files.isDirectory(worldPath, NOFOLLOW_LINKS)) { "Ground history world is not a real directory" }
            FileChannel.open(worldPath, READ).use { it.force(true) }
        }
        listOf(directory, root, requireNotNull(root.parent)).forEach {
            FileChannel.open(it, READ).use { channel -> channel.force(true) }
        }
    }

    private fun ensure(root: Path): State = state ?: run { load(root); requireNotNull(state) }
    private fun bucketStore(root: Path, world: UUID, prefix: Int) = AtomicFileStore(
        root = root, relativePath = Path.of(DIRECTORY, world.toString(), "%02x.bin".format(prefix)),
        maxBytes = MAX_BYTES.toLong(), encode = ::encode, decode = ::decode,
        validate = { ids ->
            require(ids.size in 1..CivConsumedItemIndex.MAX_BUCKET_ITEMS && ids.all { CivConsumedItemIndex.prefix(it) == prefix })
            require(ids.zipWithNext().all { (a, b) -> a < b }) { "Ground history must contain sorted unique UUIDs" }
        },
    )

    companion object {
        const val MAX_WORLDS = 64
        private const val DIRECTORY = "consumed-ground"
        private const val MAGIC = 0x43495647
        private const val VERSION = 1
        private const val MAX_BYTES = 16 + CivConsumedItemIndex.MAX_BUCKET_ITEMS * 16
        private const val MAX_DISK_BYTES = 64L * 1024 * 1024
        private val BUCKET_FILE = Regex("[0-9a-f]{2}\\.bin")
        private val TEMP_FILE = Regex("\\.[0-9a-f]{2}\\.bin-[0-9]+\\.tmp")

        internal fun encode(ids: List<UUID>): ByteArray {
            require(ids.size in 1..CivConsumedItemIndex.MAX_BUCKET_ITEMS)
            val data = ByteBuffer.allocate(16 + ids.size * 16)
            data.putInt(MAGIC).putInt(VERSION).putInt(ids.size)
            ids.forEach { data.putLong(it.mostSignificantBits).putLong(it.leastSignificantBits) }
            data.putInt(CRC32().apply { update(data.array(), 0, data.position()) }.value.toInt())
            return data.array()
        }

        internal fun decode(bytes: ByteArray): List<UUID> {
            require(bytes.size in 32..MAX_BYTES) { "Invalid ground history length" }
            val data = ByteBuffer.wrap(bytes)
            require(data.int == MAGIC && data.int == VERSION) { "Unsupported ground history header" }
            val count = data.int
            require(count in 1..CivConsumedItemIndex.MAX_BUCKET_ITEMS && bytes.size == 16 + count * 16) { "Invalid ground history count" }
            require(ByteBuffer.wrap(bytes, bytes.size - 4, 4).int == CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()) {
                "Ground history checksum mismatch"
            }
            return List(count) { UUID(data.long, data.long) }
        }
    }
}
