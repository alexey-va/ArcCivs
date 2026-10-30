package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketLease
import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/** The exact receipt remains authoritative while a transient native persistence failure is retried. */
internal class CivsNativePersistenceRetry(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal fun civsNativePersistenceFailure(failure: Throwable): Throwable {
    val cause = when (failure) {
        is InvocationTargetException, is CompletionException -> failure.cause?.let(::civsNativePersistenceFailure) ?: failure
        else -> failure
    }
    return if (cause is IOException) CivsNativePersistenceRetry("Native region IO is temporarily unavailable", cause) else cause
}

/**
 * Purpur 1.21.11-2568-f57bd86 only. A holder save schedules work; it does not acknowledge disk persistence.
 * We serialize only the affected loaded holders, then verify their real region records on the region IO queue.
 * Never use loadDataAsync here: Moonrise may return a pending payload even after its disk write failed.
 */
internal class CivsNativePersistence(
    private val plugin: Plugin,
    private val retain: (PaperChunkKey) -> PaperChunkTicketLease,
) : AutoCloseable {
    private data class ItemProof(val bytes: ByteArray?)
    private data class GroundProof(val id: UUID, val item: ItemProof)
    private data class SlotProof(val position: CivPosition, val type: String, val slot: Int, val item: ItemProof)
    private data class Target(val x: Int, val z: Int, val entities: Boolean,
        val ground: List<GroundProof>, val slots: List<SlotProof>)
    private class Pending(val result: CompletableFuture<Unit>, val leases: MutableList<PaperChunkTicketLease>,
        val tasks: LifecycleTaskScope)
    private val binding by lazy { Binding() }
    private val pending = linkedSetOf<Pending>()
    private var closed = false

    fun persist(transaction: CivInventoryTransaction, before: Boolean): CompletableFuture<Unit> {
        check(Bukkit.isPrimaryThread())
        check(!closed)
        val transfer = transaction.collected
        val containerChanges = if (transaction.containerRelocation != null) transaction.containerRelocation.containers.flatMap { holder ->
            (0 until STORAGE_SLOTS).map { slot -> CivNativeContainerChange(holder.position, holder.type, slot, holder.before[slot], holder.after[slot]) }
        } else if (transaction.cargoDropoff != null || transaction.cargoPickup != null) transaction.changes.map { change ->
            CivNativeContainerChange(change.container, change.containerType, change.slot,
                change.before?.let { CivResidentItem(it.material, it.amount) },
                change.after?.let { CivResidentItem(it.material, it.amount) })
        } else transfer?.containers.orEmpty()
        if (transfer == null && containerChanges.isEmpty()) return CompletableFuture.completedFuture(Unit)
        // No partial Item tail may migrate to an unjournalled chunk while this asynchronous fence runs.
        require(transfer?.ground.orEmpty().all { it.after == null }) { "Partial ground receipts cannot use this persistence adapter" }
        val world = plugin.server.getWorld(transaction.after.worldId) ?: throw CivsRejection("storage-unavailable")
        check(plugin.server.version.contains("1.21.11-2568-f57bd86")) { "Unsupported native item persistence runtime: ${plugin.server.version}" }
        val bytes = hashMapOf<CivResidentItem, ByteArray>()
        fun proof(item: CivResidentItem?) = ItemProof(item?.let { bytes.getOrPut(it) { it.stack().serializeAsBytes() } })
        val targets = transfer?.ground.orEmpty().groupBy { (it.position.x shr 4) to (it.position.z shr 4) }.map { (chunk, changes) ->
            Target(chunk.first, chunk.second, true, changes.map { GroundProof(it.entityId, proof(if (before) it.before else null)) }, emptyList())
        } + containerChanges.groupBy { (it.container.x shr 4) to (it.container.z shr 4) }.map { (chunk, changes) ->
            Target(chunk.first, chunk.second, false, emptyList(), changes.map {
                SlotProof(it.container, it.containerType, it.slot, proof(if (before) it.before else it.after))
            })
        }
        val chunks = targets.map { PaperChunkKey(world.uid, it.x, it.z) }.distinct()
        require(chunks.size in 1..8)
        val native = binding
        val level = native.level(world)
        val controllers = targets.associateWith { native.controller(level, it.entities) }
        val result = CompletableFuture<Unit>()
        val operation = Pending(result, mutableListOf(), LifecycleTaskScope(BukkitTaskScheduler(plugin)))
        pending += operation
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(15)
        fun finish(failure: Throwable?) {
            check(Bukkit.isPrimaryThread())
            if (!pending.remove(operation)) return
            operation.tasks.close()
            var problem = failure?.let(::civsNativePersistenceFailure)
            operation.leases.asReversed().forEach { lease -> runCatching { lease.close() }.onFailure {
                if (problem == null) problem = it else problem!!.addSuppressed(it)
            } }
            CivsDiagnostics.event("resident_item_persist", "city" to transaction.cityId, "receipt" to transaction.id,
                "phase" to if (before) "before-journal" else "after-native", "chunks" to chunks.size,
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000,
                "result" to if (problem == null) "acknowledged" else "unknown")
            if (problem == null) result.complete(Unit) else result.completeExceptionally(problem!!)
        }
        try {
            chunks.forEach { key ->
                check(world.isChunkLoaded(key.x, key.z)) { "Persistence source chunk is unloaded" }
                operation.leases += retain(key)
            }
            var cursor = 0
            var io: CompletableFuture<Unit>? = null
            checkNotNull(operation.tasks.runTimer(1L, 1L) pulse@{
                if (closed || result.isCancelled) return@pulse finish(IllegalStateException("Native persistence cancelled"))
                if (System.nanoTime() >= deadline) return@pulse finish(CivsNativePersistenceRetry("Native persistence timed out"))
                runCatching {
                    if (cursor < chunks.size) {
                        val key = chunks[cursor++]
                        check(world.isChunkLoaded(key.x, key.z)) { "Persistence source chunk is unloaded" }
                        val serialStarted = System.nanoTime()
                        native.save(world.getChunkAt(key.x, key.z)) // At most one loaded holder per owner-thread tick.
                        if (System.nanoTime() - serialStarted >= 10_000_000) CivsDiagnostics.record(plugin.logger, java.util.logging.Level.WARNING, transaction.cityId,
                            "ArcCivs resident item chunk serialization exceeded 10ms at ${key.x},${key.z}")
                    } else if (io == null && targets.none { native.hasTask(controllers.getValue(it), it.x, it.z) }) {
                        // The pinned scheduleSave registers each chunk task before holder.save returns, including
                        // pending serialization, and removes it only after finishWrite. Poll only our targets;
                        // unrelated world IO must not delay this receipt. Cold reads still run on its region queue.
                        io = targets.fold(CompletableFuture.completedFuture(Unit)) { previous, target ->
                            previous.thenCompose {
                                val checked = CompletableFuture<Unit>()
                                try {
                                    native.regionTask(controllers.getValue(target), target.x, target.z) {
                                        try {
                                            check(!result.isDone) { "Native persistence readback cancelled" }
                                            if (System.nanoTime() >= deadline) throw CivsNativePersistenceRetry("Native persistence readback expired")
                                            native.verify(controllers.getValue(target), target)
                                            checked.complete(Unit)
                                        } catch (failure: Throwable) { checked.completeExceptionally(failure) }
                                    }
                                } catch (failure: Throwable) { checked.completeExceptionally(failure) }
                                checked
                            }
                        }
                    }
                    io?.takeIf { it.isDone }?.let { completed ->
                        finish(runCatching { completed.join() }.exceptionOrNull())
                    }
                }.onFailure { finish(it) }
            }) { "Native persistence scope is inactive" }
        } catch (failure: Throwable) { finish(failure) }
        return result
    }

    /** Called before the shared chunk registry is reset or closed. Native queued reads cannot publish city state. */
    fun cancelPending() {
        check(Bukkit.isPrimaryThread())
        pending.toList().forEach { operation ->
            pending.remove(operation)
            operation.tasks.close()
            operation.leases.asReversed().forEach { runCatching { it.close() }.onFailure { failure ->
                plugin.logger.log(java.util.logging.Level.WARNING, "ArcCivs could not release persistence chunk", failure)
            } }
            operation.result.completeExceptionally(IllegalStateException("Native persistence cancelled"))
        }
    }

    override fun close() { closed = true; cancelPending() }

    private class Binding {
        private fun type(name: String) = Class.forName(name)
        private val levelType = type("net.minecraft.server.level.ServerLevel")
        private val craftWorld = type("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle")
        private val statusType = type("net.minecraft.world.level.chunk.status.ChunkStatus")
        private val full = statusType.getField("FULL").get(null)
        private val craftChunk = type("org.bukkit.craftbukkit.CraftChunk").getMethod("getHandle", statusType)
        private val holder = type("ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk")
            .getMethod("moonrise\$getChunkHolder")
        private val save = type("ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder")
            .getMethod("save", Boolean::class.javaPrimitiveType)
        private val io = type("ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO")
        private val regionType = type("ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO\$RegionFileType")
        private val controllerType = type("ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO\$RegionDataController")
        private val getController = io.getMethod("getControllerFor", levelType, regionType)
        private val entityType = regionType.getField("ENTITY_DATA").get(null)
        private val chunkType = regionType.getField("CHUNK_DATA").get(null)
        private val chunkTasks = controllerType.getDeclaredField("chunkTasks").apply { isAccessible = true }
        private val getTask = type("ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable")
            .getMethod("get", Long::class.javaPrimitiveType)
        private val chunkKey = type("ca.spottedleaf.moonrise.common.util.CoordinateUtils")
            .getMethod("getChunkKey", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val getCache = controllerType.getMethod("getCache")
        private val priorityType = type("ca.spottedleaf.concurrentutil.util.Priority")
        private val normalPriority = priorityType.getField("NORMAL").get(null)
        private val createTask = controllerType.getMethod("createRegionIoTask", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Runnable::class.java, priorityType)
        private val queue = type("ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor\$PrioritisedTask").getMethod("queue")
        private val cacheType = type("net.minecraft.world.level.chunk.storage.RegionFileStorage")
        private val existingRegion = cacheType.getMethod("moonrise\$getRegionFileIfExists", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val regionFile = type("net.minecraft.world.level.chunk.storage.RegionFile")
        private val flush = regionFile.getMethod("flush")
        private val channel = regionFile.getDeclaredField("file").apply { isAccessible = true }
        private val path = regionFile.getDeclaredField("path").apply { isAccessible = true }
        private val offsets = regionFile.getDeclaredField("offsets").apply { isAccessible = true }
        private val readData = controllerType.getMethod("readData", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        private val readType = type("ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO\$RegionDataController\$ReadData")
        private val readResult = readType.getMethod("result")
        private val readInput = readType.getMethod("input")
        private val compound = type("net.minecraft.nbt.CompoundTag")
        private val getList = compound.getMethod("getList", String::class.java)
        private val contains = compound.getMethod("contains", String::class.java)
        private val getCompound = compound.getMethod("getCompound", String::class.java)
        private val getString = compound.getMethod("getString", String::class.java)
        private val getInt = compound.getMethod("getInt", String::class.java)
        private val getByte = compound.getMethod("getByte", String::class.java)
        private val getInts = compound.getMethod("getIntArray", String::class.java)
        private val copy = compound.getMethod("copy")
        private val remove = compound.getMethod("remove", String::class.java)
        private val accounter = type("net.minecraft.nbt.NbtAccounter")
        private val quota = accounter.getMethod("create", Long::class.javaPrimitiveType)
        private val nbt = type("net.minecraft.nbt.NbtIo")
        private val read = nbt.getMethod("read", DataInput::class.java, accounter)
        private val compressed = nbt.getMethod("readCompressed", java.io.InputStream::class.java, accounter)

        fun level(world: World): Any = craftWorld.invoke(world)
        fun controller(level: Any, entities: Boolean): Any = getController.invoke(null, level, if (entities) entityType else chunkType)
        fun hasTask(controller: Any, x: Int, z: Int): Boolean =
            getTask.invoke(chunkTasks.get(controller), chunkKey.invoke(null, x, z)) != null
        fun save(chunk: Chunk) { save.invoke(holder.invoke(craftChunk.invoke(chunk, full)), false) }
        fun regionTask(controller: Any, x: Int, z: Int, action: () -> Unit) {
            check(queue.invoke(createTask.invoke(controller, x, z, Runnable(action), normalPriority)) == true) { "Region readback task rejected" }
        }

        fun verify(controller: Any, target: Target) {
            val region = existingRegion.invoke(getCache.invoke(controller), target.x, target.z)
            val data = if (region == null) null else {
                flush.invoke(region) // Propagates force failure; no whole-world traversal or serialization.
                // A newly created region must have its directory entry acknowledged as well as its contents.
                FileChannel.open((path.get(region) as Path).parent, StandardOpenOption.READ).use { it.force(true) }
                val index = (target.x and 31) + (target.z and 31) * 32
                val offset = (offsets.get(region) as java.nio.IntBuffer).get(index)
                // Native writes update their cached header before writeHeader; a failed header must not be acknowledged.
                civsVerifyRegionHeader(channel.get(region) as FileChannel, index, offset)
                val readState = readData.invoke(controller, target.x, target.z)
                when (readResult.invoke(readState).toString()) {
                    "NO_DATA" -> { check(offset == 0) { "Native region record is missing" }; null }
                    "HAS_DATA" -> (readInput.invoke(readState) as DataInputStream).use {
                        read.invoke(null, it, quota.invoke(null, 16L * 1024 * 1024))
                    }
                    else -> error("External or oversized native record cannot be acknowledged")
                }
            }
            if (target.entities) verifyGround(data, target) else verifySlots(data, target)
        }

        private fun value(method: java.lang.reflect.Method, tag: Any, key: String): Any? =
            (method.invoke(tag, key) as Optional<*>).orElse(null)
        private fun list(tag: Any, key: String): List<Any> {
            val found = value(getList, tag, key) ?: run {
                check(contains.invoke(tag, key) != true) { "Malformed native NBT list" }
                return emptyList()
            }
            return (found as Iterable<*>).map { requireNotNull(it).also { item -> check(compound.isInstance(item)) } }
        }
        private fun expected(item: ItemProof): Any? = item.bytes?.let { bytes ->
            compressed.invoke(null, ByteArrayInputStream(bytes), quota.invoke(null, 1L * 1024 * 1024)).also { remove.invoke(it, "DataVersion") }
        }

        private fun verifyGround(data: Any?, target: Target) {
            val found = target.ground.associate { it.id to mutableListOf<Any>() }
            if (data != null) {
                val position = value(getInts, data, "Position") as? IntArray
                check(position != null && position.contentEquals(intArrayOf(target.x, target.z))) { "Entity region coordinates mismatch" }
                check(value(getList, data, "Entities") != null) { "Entity region list is missing" }
                val queue = ArrayDeque(list(data, "Entities"))
                var count = 0
                while (queue.isNotEmpty()) {
                    check(++count <= 65_536) { "Entity record exceeds bounded inspection" }
                    val entity = queue.removeFirst()
                    val uuid = value(getInts, entity, "UUID") as? IntArray
                    if (uuid != null && uuid.size == 4) {
                        val id = UUID((uuid[0].toLong() shl 32) or (uuid[1].toLong() and 0xffffffffL),
                            (uuid[2].toLong() shl 32) or (uuid[3].toLong() and 0xffffffffL))
                        found[id]?.add(entity)
                    }
                    queue.addAll(list(entity, "Passengers"))
                }
            }
            target.ground.forEach { proof ->
                val matches = found.getValue(proof.id)
                val item = expected(proof.item)
                if (item == null) check(matches.isEmpty()) { "Removed Item is still on disk" }
                else {
                    check(matches.size == 1) { "Source Item missing or duplicated on disk" }
                    val entity = matches.single()
                    check(value(getString, entity, "id") == "minecraft:item" && value(getCompound, entity, "Item") == item) {
                        "Source Item on disk differs from the receipt"
                    }
                }
            }
        }

        private fun verifySlots(data: Any?, target: Target) {
            check(data != null && value(getInt, data, "xPos") == target.x && value(getInt, data, "zPos") == target.z) {
                "Container chunk missing or at different coordinates"
            }
            val entities = list(data, "block_entities")
            check(entities.size <= 65_536)
            target.slots.groupBy { it.position }.forEach { (position, proofs) ->
                val containers = entities.filter { value(getInt, it, "x") == position.x &&
                    value(getInt, it, "y") == position.y && value(getInt, it, "z") == position.z }
                check(containers.size == 1 && value(getString, containers.single(), "id") == "minecraft:${proofs.first().type.lowercase()}") {
                    "Native container missing, duplicated or replaced on disk"
                }
                val slots = list(containers.single(), "Items")
                check(slots.size <= 256)
                proofs.forEach { proof ->
                    val matches = slots.filter { ((value(getByte, it, "Slot") as? Byte)?.toInt()?.and(255) ?: 0) == proof.slot }
                    check(matches.size <= 1) { "Duplicate native container slot" }
                    val actual = matches.singleOrNull()?.let { copy.invoke(it).also { tag -> remove.invoke(tag, "Slot") } }
                    check(actual == expected(proof.item)) { "Native container slot on disk differs from the receipt" }
                }
            }
        }
    }
}

/** Cold on-disk header proof; native cached offsets alone survive a failed writeHeader and are not an ACK. */
internal fun civsVerifyRegionHeader(channel: FileChannel, index: Int, expectedOffset: Int) {
    require(index in 0 until 1024)
    fun readAt(position: Long, size: Int): ByteBuffer = ByteBuffer.allocate(size).also { buffer ->
        while (buffer.hasRemaining()) check(channel.read(buffer, position + buffer.position()) > 0) { "Truncated native region file" }
        buffer.flip()
    }
    check(readAt(index * 4L, 4).int == expectedOffset) { "Native cached header differs from disk" }
    if (expectedOffset == 0) return
    val sector = expectedOffset ushr 8
    val sectors = expectedOffset and 255
    check(sector >= 2 && sectors > 0) { "Invalid native region record" }
    val header = readAt(sector * 4096L, 5)
    val length = header.int
    val compression = header.get().toInt() and 255
    check(length in 1..(sectors * 4096 - 4) && sector * 4096L + 4 + length <= channel.size()) { "Truncated native region record" }
    check(compression and 128 == 0) { "External native record requires a separate durability proof" }
}
