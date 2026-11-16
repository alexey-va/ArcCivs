package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.verify
import ru.arc.persistence.AtomicFileStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsStoreCheckpointTest : FreeSpec({
    lateinit var root: Path
    val direct = Executor(Runnable::run)
    beforeEach { root = Files.createTempDirectory("civs-checkpoint-") }
    afterEach { root.toFile().deleteRecursively() }

    "ordinary revisions remain in RAM and one flush writes only the latest snapshot" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val original = Files.readAllBytes(cityFile(root, city))
        val first = city.copy(revision = 1, workers = city.workers.map { it.copy(hunger = 19) })
        val latest = first.copy(revision = 2, workers = first.workers.map { it.copy(hunger = 18) })
        store.stage(first).join() shouldBe first
        store.stage(latest).join() shouldBe latest
        Files.readAllBytes(cityFile(root, city)).contentEquals(original) shouldBe true
        // A fresh process before the flush loses only the permitted ordinary suffix.
        CivsStore(root, direct).load().join() shouldBe listOf(city)
        store.flush().join()
        CivsStore(root, direct).load().join() shouldBe listOf(latest)
        shouldThrow<CompletionException> { store.stage(latest.copy(revision = 4)).join() }
        shouldThrow<CompletionException> { store.stage(latest.copy(revision = 3, name = "Changed directory")).join() }
    }

    "five minute checkpoints stagger three cities and admit only one latest dirty snapshot per pulse" {
        var now = 1_000_000L
        val store = CivsStore(root, direct, checkpointClock = { now })
        val cities = listOf(10_000L, 110_000L, 210_000L).map { checkpointCity().copy(id = UUID(0L, it)) }
        cities.forEach { store.save(it).join() }
        store.flushNext().join()
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 0L
        val dirty = cities.map { it.copy(revision = 1) }
        dirty.forEach { store.stage(it).join() }
        val latest = dirty.first().copy(revision = 2)
        store.stage(latest).join()
        now += 9_999
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 3
        now += 1
        store.flushNext().join()
        CivsStore(root, direct).load().join().associateBy { it.id } shouldBe
            mapOf(latest.id to latest, cities[1].id to cities[1], cities[2].id to cities[2])
        store.checkpointStats().dirtyCities shouldBe 2
        store.flushNext().join()
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 1L

        // Two deadlines are overdue, but one pulse still writes only one city.
        now = 1_210_000L
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 1
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 2L
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 0
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 3L
        val later = dirty[1].copy(revision = 2)
        store.stage(later).join()
        now = 1_410_000L // Its original deadline + five minutes is not the new deadline.
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 1
        now = 1_509_999L
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 1
        now += 1
        store.flushNext().join()
        val stats = store.checkpointStats()
        stats.dirtyCities shouldBe 0
        stats.fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 4L
        stats.fullCityWrites[CivsCheckpointReason.EXPLICIT_SAVE] shouldBe 3L
        now += 1_000_000L
        store.flushNext().join()
        store.checkpointStats() shouldBe stats
    }

    "a failed periodic checkpoint isolates another due city and waits five minutes before retry" {
        var now = 1_000_000L
        val city = checkpointCity().copy(id = UUID(0L, 10_000L))
        val other = checkpointCity().copy(id = UUID(0L, 110_000L))
        val store = CivsStore(root, direct, checkpointClock = { now })
        store.save(city).join()
        store.save(other).join()
        val latest = city.copy(revision = 1)
        val otherLatest = other.copy(revision = 1)
        store.stage(latest).join()
        store.stage(otherLatest).join()
        val path = cityFile(root, city)
        val original = Files.readAllBytes(path)
        Files.delete(path)
        Files.createDirectory(path)
        now += 10_000L
        val failure = shouldThrow<CompletionException> { store.flushNext().join() }.cause as CivsCheckpointFailure
        failure.failures.keys shouldBe setOf(city.id)
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 0L
        // The broken path stays broken: its cooldown must neither rescan it nor block another due city.
        store.flushNext().join()
        now += 100_000L
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 1
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 1L
        Files.delete(path)
        Files.write(path, original)
        now = 1_309_999L
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 1
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 1L
        now += 1
        store.flushNext().join()
        store.checkpointStats().dirtyCities shouldBe 0
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 2L
        CivsStore(root, direct).load().join().associateBy { it.id } shouldBe mapOf(city.id to latest, other.id to otherLatest)
    }

    "native admission first checkpoints intervening ordinary revisions" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val rested = city.copy(revision = 1, workers = city.workers.map { it.copy(hunger = 19) })
        store.stage(rested).join()
        val tx = checkpointTransaction(rested, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.saveInventoryTransaction(tx).join()
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.BEFORE_NATIVE] shouldBe 1L
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.PERIODIC] shouldBe 0L
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe listOf(tx)
        reopened.load().join() shouldBe listOf(rested)
        shouldThrow<CompletionException> { store.stage(tx.after).join() }
    }

    "native completion does not encode a second full city and ordinary revisions do not encode at all" {
        mockkConstructor(Gson::class, localToThread = true) {
            every { anyConstructed<Gson>().toJson(match<Any> { it.javaClass.simpleName == "CityDocument" }) } answers { callOriginal() }
            val city = checkpointCity()
            val store = CivsStore(root, direct)
            store.save(city).join()
            store.stage(city.copy(revision = 1)).join()
            store.stage(city.copy(revision = 2)).join()
            verify(exactly = 1) { anyConstructed<Gson>().toJson(match<Any> { it.javaClass.simpleName == "CityDocument" }) }
            store.flush().join()
            val tx = checkpointTransaction(city.copy(revision = 2), 3, 2)
            store.saveInventoryTransaction(tx).join()
            store.completeInventoryTransaction(tx).join()
            store.deleteInventoryTransaction(tx.id).join()
            // Initial city + one coalesced flush + the existing receipt admission size check.
            verify(exactly = 3) { anyConstructed<Gson>().toJson(match<Any> { it.javaClass.simpleName == "CityDocument" }) }
        }
    }

    "completed native receipt supplies the durable city without rewriting its full snapshot" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val original = Files.readAllBytes(cityFile(root, city))
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.completeInventoryTransaction(tx).join() shouldBe tx.after
        store.deleteInventoryTransaction(tx.id).join() shouldBe true
        Files.readAllBytes(cityFile(root, city)).contentEquals(original) shouldBe true
        Files.exists(receiptFile(root, tx)) shouldBe true
        (Files.size(root.resolve("inventory-completed/${city.id}.json")) < 1_024L) shouldBe true
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        reopened.load().join() shouldBe listOf(tx.after)
        shouldThrow<CompletionException> { store.saveInventoryTransaction(tx.copy(changes = emptyList())).join() }
    }

    "two successive transactions on the same native slot never replay the first after head replacement" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val first = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(first).join()
        store.completeInventoryTransaction(first).join()
        store.deleteInventoryTransaction(first.id).join()
        val second = checkpointTransaction(first.after, 2, 1)
        store.saveInventoryTransaction(second).join()

        // Crash while the second native transfer is pending: the first is the exact durable before-city.
        val pendingRestart = CivsStore(root, direct)
        pendingRestart.loadInventoryTransactions().join() shouldBe listOf(second)
        pendingRestart.load().join() shouldBe listOf(first.after)
        store.completeInventoryTransaction(second).join()

        // Crash after the second head ACK but before retirement of the first receipt.
        Files.exists(receiptFile(root, first)) shouldBe true
        Files.exists(receiptFile(root, second)) shouldBe true
        val completedRestart = CivsStore(root, direct)
        completedRestart.loadInventoryTransactions().join() shouldBe emptyList()
        completedRestart.load().join() shouldBe listOf(second.after)
        store.deleteInventoryTransaction(second.id).join()
        Files.exists(receiptFile(root, first)) shouldBe false
        Files.exists(receiptFile(root, second)) shouldBe true
    }

    "periodic snapshot covers and retires the receipt while its small watermark remains valid" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.completeInventoryTransaction(tx).join()
        val ordinary = tx.after.copy(revision = 2, workers = tx.after.workers.map { it.copy(hunger = 18) })
        store.stage(ordinary).join()
        store.flush().join()
        Files.exists(receiptFile(root, tx)) shouldBe false
        Files.exists(root.resolve("inventory-completed/${city.id}.json")) shouldBe true
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        reopened.load().join() shouldBe listOf(ordinary)
    }

    "missing completed payload fails closed until a durable city snapshot covers the head" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.completeInventoryTransaction(tx).join()
        Files.delete(receiptFile(root, tx))
        shouldThrow<CompletionException> { CivsStore(root, direct).loadInventoryTransactions().join() }
        shouldThrow<CompletionException> { CivsStore(root, direct).load().join() }
        store.flush().join()
        CivsStore(root, direct).loadInventoryTransactions().join() shouldBe emptyList()
        CivsStore(root, direct).load().join() shouldBe listOf(tx.after)
    }

    "a covered full snapshot ignores corrupt obsolete receipt bytes during bootstrap" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.completeInventoryTransaction(tx).join()
        store.flush().join()
        Files.writeString(receiptFile(root, tx), "{obsolete broken bytes")
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        reopened.load().join() shouldBe listOf(tx.after)
        val next = checkpointTransaction(tx.after, 2, 1)
        reopened.saveInventoryTransaction(next).join()
        Files.exists(receiptFile(root, tx)) shouldBe false
        reopened.loadInventoryTransactions().join() shouldBe listOf(next)
    }

    "an uncertain head acknowledgement recovers its completed state without replaying the native receipt" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        mockkConstructor(AtomicFileStore::class, localToThread = true) {
            every { anyConstructed<AtomicFileStore<Any>>().write(match { it.javaClass.simpleName == "InventoryCompletionHead" }) } answers {
                callOriginal()
                throw IOException("completion ACK lost after durable rename")
            }
            shouldThrow<CompletionException> { store.completeInventoryTransaction(tx).join() }
        }
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        reopened.load().join() shouldBe listOf(tx.after)
        store.completeInventoryTransaction(tx).join() shouldBe tx.after
    }

    "flush failure keeps the newest RAM state and still checkpoints unrelated cities" {
        val city = checkpointCity()
        val other = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        store.save(other).join()
        val next = city.copy(revision = 1, workers = city.workers.map { it.copy(hunger = 19) })
        val otherNext = other.copy(revision = 1, workers = other.workers.map { it.copy(hunger = 18) })
        store.stage(next).join()
        store.stage(otherNext).join()
        val path = cityFile(root, city)
        val original = Files.readAllBytes(path)
        Files.delete(path)
        Files.createDirectory(path)
        val failure = shouldThrow<CompletionException> { store.flush().join() }.cause as CivsCheckpointFailure
        failure.failures.keys shouldBe setOf(city.id)
        Files.delete(path)
        Files.write(path, original)
        store.flush().join()
        CivsStore(root, direct).load().join().associateBy { it.id } shouldBe mapOf(city.id to next, other.id to otherNext)
    }

    "close drains already accepted buffered changes without a Main thread continuation" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val queue = ArrayDeque<Runnable>()
        val store = CivsStore(root, Executor { queue.addLast(it) })
        val first = city.copy(revision = 1, workers = city.workers.map { it.copy(hunger = 19) })
        val latest = first.copy(revision = 2, workers = first.workers.map { it.copy(hunger = 18) })
        val staged = listOf(store.stage(first), store.stage(latest))
        store.close()
        store.drained().isDone shouldBe false
        while (queue.isNotEmpty()) queue.removeFirst().run()
        staged.map { it.join() } shouldBe listOf(first, latest)
        store.drained().join()
        CivsStore(root, direct).load().join() shouldBe listOf(latest)
        shouldThrow<CompletionException> { store.stage(latest.copy(revision = 3)).join() }
    }
})

private fun cityFile(root: Path, city: Settlement) = root.resolve("cities/${city.id}.json")
private fun receiptFile(root: Path, tx: CivInventoryTransaction) = root.resolve("inventory-transactions/${tx.id}.json")

private fun checkpointCity(): Settlement {
    val control = CivPosition(0, 64, 0)
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, control, 0, "test",
        CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -2),
        BuildingPhase.ACTIVE, control = control)
    return CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Checkpoint", UUID.randomUUID(),
        control, 16, "nordic", Stock(), listOf(hall), listOf(CivWorker(UUID.randomUUID(), "Resident", null, hunger = 20)),
        inventory = CivInventory(CivStorageMode.PHYSICAL)))
}

private fun checkpointTransaction(city: Settlement, before: Int, after: Int) = CivInventoryTransaction(
    UUID.randomUUID(), city.id, city.revision, city.copy(revision = city.revision + 1),
    listOf(CivInventorySlotChange(requireNotNull(city.buildings.single().control), "BARREL", 0,
        CivPlainItem("OAK_LOG", before), CivPlainItem("OAK_LOG", after))),
).validated()

/** Existing journal fixtures control durable acknowledgements through their save future; real files are tested above. */
internal fun CivsStore.stubCheckpointAcknowledgements() {
    val store = this
    every { store.completeInventoryTransaction(any()) } answers { store.save(firstArg<CivInventoryTransaction>().after) }
    every { store.stage(any()) } answers { store.save(firstArg()) }
    every { store.flush() } returns CompletableFuture.completedFuture(Unit)
    every { store.flushNext() } returns CompletableFuture.completedFuture(Unit)
    every { store.checkpointStats() } returns CivsCheckpointStats(0, CivsCheckpointReason.entries.associateWith { 0L })
}
