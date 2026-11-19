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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    "ordinary and two same-slot native operations use RAM without JSON or filesystem access" {
        mockkConstructor(Gson::class, localToThread = true) {
            val city = checkpointCity()
            val store = CivsStore(root, direct)
            store.save(city).join()
            store.loadInventoryTransactions().join()
            store.loadConsumedItems().join()
            val original = Files.readAllBytes(cityFile(root, city))
            val hidden = root.resolveSibling(root.fileName.toString() + "-hidden")
            Files.move(root, hidden)
            Files.writeString(root, "No filesystem operations are available here")
            val latest: Settlement
            try {
                val rested = city.copy(revision = 1, workers = city.workers.map { it.copy(hunger = 19) })
                store.stage(rested).join()
                val first = checkpointTransaction(rested, 3, 2)
                store.saveInventoryTransaction(first).join() shouldBe first
                store.saveInventoryTransaction(first).join() shouldBe first
                shouldThrow<CompletionException> { store.saveInventoryTransaction(first.copy(id = UUID.randomUUID())).join() }
                store.completeInventoryTransaction(first).join() shouldBe first.after
                store.completeInventoryTransaction(first).join() shouldBe first.after
                store.deleteInventoryTransaction(first.id).join() shouldBe true
                val second = checkpointTransaction(first.after, 2, 1)
                store.saveInventoryTransaction(second).join()
                latest = store.completeInventoryTransaction(second).join()
                store.deleteInventoryTransaction(second.id).join() shouldBe true
                verify(exactly = 1) { anyConstructed<Gson>().toJson(any<Any>()) }
                store.checkpointStats().fullCityWrites[CivsCheckpointReason.BEFORE_NATIVE] shouldBe 0L
                store.checkpointStats().dirtyCities shouldBe 1
            } finally {
                Files.delete(root)
                Files.move(hidden, root)
            }
            Files.readAllBytes(cityFile(root, city)).contentEquals(original) shouldBe true
            Files.exists(root.resolve("inventory-transactions")) shouldBe false
            Files.exists(root.resolve("inventory-completed")) shouldBe false
            // An abrupt restart may roll back this suffix, but must never invent a receipt or replay native slots.
            CivsStore(root, direct).loadInventoryTransactions().join() shouldBe emptyList()
            CivsStore(root, direct).load().join() shouldBe listOf(city)
            store.flush().join()
            CivsStore(root, direct).load().join() shouldBe listOf(latest)
        }
    }

    "completed RAM intent graphs retire while only the latest city remains dirty" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        var latest = city
        repeat(100) {
            val tx = checkpointTransaction(latest, 3, 2)
            store.saveInventoryTransaction(tx).join()
            latest = store.completeInventoryTransaction(tx).join()
            store.deleteInventoryTransaction(tx.id).join()
        }
        val field = CivsStore::class.java.getDeclaredField("activeReceipts").apply { isAccessible = true }
        (field.get(store) as Map<*, *>).size shouldBe 0
        store.loadInventoryTransactions().join() shouldBe emptyList()
        store.checkpointStats().dirtyCities shouldBe 1
        store.checkpointStats().fullCityWrites[CivsCheckpointReason.EXPLICIT_SAVE] shouldBe 1L
        store.flush().join()
        CivsStore(root, direct).load().join() shouldBe listOf(latest)
    }

    "two local native commits finish while storage holds the previous checkpoint acknowledgement" {
        val city = checkpointCity()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holdNextClockRead = AtomicBoolean(false)
        val io = Executors.newSingleThreadExecutor()
        val store = CivsStore(root, io, checkpointClock = {
            if (holdNextClockRead.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            0L
        })
        try {
            store.save(city).join()
            store.load().join()
            val first = checkpointTransaction(city, 3, 2)
            store.beginLocalInventory(city, first)
            store.completeLocalInventory(first) shouldBe first.after
            holdNextClockRead.set(true)
            val checkpoint = store.flush()
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val second = checkpointTransaction(first.after, 2, 1)
            val third = checkpointTransaction(second.after, 1, 3)
            // The file worker is holding ioLock after the first revision reached disk.
            val latest = CompletableFuture.supplyAsync {
                store.beginLocalInventory(first.after, second)
                store.completeLocalInventory(second)
                store.beginLocalInventory(second.after, third)
                store.completeLocalInventory(third)
            }.get(3, TimeUnit.SECONDS)
            latest shouldBe third.after
            store.completeLocalInventory(third) shouldBe latest
            shouldThrow<IllegalStateException> { store.completeLocalInventory(second) }
            shouldThrow<CivsRejection> { store.beginLocalInventory(city, first) }
            release.countDown()
            checkpoint.join()
            CivsStore(root, direct).load().join() shouldBe listOf(first.after)
            store.checkpointStats().dirtyCities shouldBe 1
            store.close()
            store.drained().join()
            CivsStore(root, direct).load().join() shouldBe listOf(third.after)
        } finally {
            release.countDown()
            io.shutdownNow()
        }
    }

    "a local native reservation survives reload and only its exact holder can resolve a partial transfer" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        store.load().join()
        val tx = checkpointTransaction(city, 3, 2)
        store.beginLocalInventory(city, tx)
        shouldThrow<CivsRejection> { store.beginLocalInventory(city, tx) }
        shouldThrow<CompletionException> { store.stage(tx.after).join() }
        shouldThrow<CompletionException> { store.save(tx.after).join() }
        shouldThrow<CompletionException> { store.delete(city).join() }
        shouldThrow<CompletionException> { store.saveInventoryTransaction(tx).join() }
        store.hasLocalInventory(tx.copy()) shouldBe false
        shouldThrow<IllegalStateException> { store.completeLocalInventory(tx.copy()) }
        shouldThrow<IllegalStateException> { store.cancelLocalInventory(tx.copy()) }
        (shouldThrow<CompletionException> { store.flush().join() }.cause as CivsCheckpointFailure).failures.keys shouldBe setOf(city.id)
        val recovered = store.loadInventoryTransactions().join().single()
        (recovered === tx) shouldBe true
        store.load().join() shouldBe listOf(city)
        store.hasLocalInventory(recovered) shouldBe true
        store.cancelLocalInventory(recovered)
        store.loadInventoryTransactions().join() shouldBe emptyList()

        val replacement = tx.copy(id = UUID.randomUUID())
        store.beginLocalInventory(city, replacement)
        store.completeLocalInventory(replacement) shouldBe replacement.after
        val staged = store.stage(replacement.after.copy(revision = 2)).join()
        val legacyLane = checkpointTransaction(staged, 2, 1)
        store.saveInventoryTransaction(legacyLane).join()
        shouldThrow<IllegalArgumentException> { store.beginLocalInventory(staged, legacyLane.copy(id = UUID.randomUUID())) }
        val next = store.completeInventoryTransaction(legacyLane).join()
        store.deleteInventoryTransaction(legacyLane.id).join()
        shouldThrow<CompletionException> { store.delete(staged).join() }
        val local = checkpointTransaction(next, 1, 2)
        store.beginLocalInventory(next, local)
        store.completeLocalInventory(local) shouldBe local.after
        store.delete(local.after).join()
        store.load().join() shouldBe emptyList()
    }

    "a stopped store reports the exact unfinished local city on drain" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        store.load().join()
        val tx = checkpointTransaction(city, 3, 2)
        store.beginLocalInventory(city, tx)
        store.close()
        val failure = shouldThrow<CompletionException> { store.drained().join() }.cause as CivsCheckpointFailure
        failure.failures.keys shouldBe setOf(city.id)
        shouldThrow<CivsRejection> { store.completeLocalInventory(tx) }
        CivsStore(root, direct).load().join() shouldBe listOf(city)
    }

    "retired UUID cannot be admitted after a later revision even when native slots return to its before image" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val first = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(first).join()
        store.completeInventoryTransaction(first).join()
        store.deleteInventoryTransaction(first.id).join()
        // A later operation creates the native ABA image. Its newer city revision is still authoritative.
        val second = checkpointTransaction(first.after, 2, 3)
        store.saveInventoryTransaction(second).join()
        store.completeInventoryTransaction(second).join()
        store.deleteInventoryTransaction(second.id).join()
        shouldThrow<CompletionException> { store.saveInventoryTransaction(first).join() }
        store.loadInventoryTransactions().join() shouldBe emptyList()
        store.flush().join()
        CivsStore(root, direct).load().join() shouldBe listOf(second.after)
    }

    "same-process reload preserves an accepted RAM intent and its exact retry" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.loadInventoryTransactions().join() shouldBe listOf(tx)
        store.load().join() shouldBe listOf(city)
        store.saveInventoryTransaction(tx).join() shouldBe tx
        shouldThrow<CompletionException> { store.saveInventoryTransaction(tx.copy(id = UUID.randomUUID())).join() }
        store.completeInventoryTransaction(tx).join() shouldBe tx.after
        // A reload between acceptance and Main publication must still finish this same in-process operation.
        store.loadInventoryTransactions().join() shouldBe listOf(tx)
        store.load().join() shouldBe listOf(tx.after)
        store.completeInventoryTransaction(tx).join() shouldBe tx.after
        store.deleteInventoryTransaction(tx.id).join()
        store.loadInventoryTransactions().join() shouldBe emptyList()
    }

    "legacy pending recovery finishes durably before a new RAM operation" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val legacy = checkpointTransaction(city, 3, 2)
        writeLegacyInventoryReceipt(root, legacy)
        val store = CivsStore(root, direct)
        store.loadInventoryTransactions().join() shouldBe listOf(legacy)
        shouldThrow<CompletionException> { store.saveInventoryTransaction(legacy.copy(id = UUID.randomUUID())).join() }
        store.completeInventoryTransaction(legacy).join() shouldBe legacy.after
        store.deleteInventoryTransaction(legacy.id).join()
        Files.exists(receiptFile(root, legacy)) shouldBe false
        CivsStore(root, direct).load().join() shouldBe listOf(legacy.after)
        val next = checkpointTransaction(legacy.after, 2, 1)
        store.saveInventoryTransaction(next).join()
        store.completeInventoryTransaction(next).join()
        store.deleteInventoryTransaction(next.id).join()
        Files.exists(receiptFile(root, next)) shouldBe false
        CivsStore(root, direct).load().join() shouldBe listOf(legacy.after)
        store.flush().join()
        CivsStore(root, direct).load().join() shouldBe listOf(next.after)
    }

    "legacy completed heads consolidate before admission and never resurrect an older city" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val legacy = checkpointTransaction(city, 3, 2)
        writeLegacyInventoryReceipt(root, legacy, completed = true)
        val store = CivsStore(root, direct)
        store.loadInventoryTransactions().join() shouldBe emptyList()
        Files.exists(receiptFile(root, legacy)) shouldBe false
        CivsStore(root, direct).load().join() shouldBe listOf(legacy.after)
        val next = checkpointTransaction(legacy.after, 2, 1)
        store.saveInventoryTransaction(next).join()
        store.completeInventoryTransaction(next).join()
        store.deleteInventoryTransaction(next.id).join()
        store.flush().join()
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        reopened.load().join() shouldBe listOf(next.after)
    }

    "missing legacy completed payload fails closed until a full snapshot covers its head" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        writeLegacyInventoryReceipt(root, tx, completed = true)
        Files.delete(receiptFile(root, tx))
        shouldThrow<CompletionException> { CivsStore(root, direct).loadInventoryTransactions().join() }
        shouldThrow<CompletionException> { CivsStore(root, direct).load().join() }
        Files.writeString(cityFile(root, city), Gson().toJson(mapOf("schemaVersion" to CivsStore.SCHEMA_VERSION, "city" to tx.after)))
        CivsStore(root, direct).loadInventoryTransactions().join() shouldBe emptyList()
        CivsStore(root, direct).load().join() shouldBe listOf(tx.after)
    }

    "a covered full snapshot ignores and retires corrupt obsolete legacy receipt bytes" {
        val city = checkpointCity()
        val tx = checkpointTransaction(city, 3, 2)
        CivsStore(root, direct).apply { save(city).join(); save(tx.after).join() }
        writeLegacyInventoryReceipt(root, tx, completed = true)
        Files.writeString(receiptFile(root, tx), "{obsolete broken bytes")
        val reopened = CivsStore(root, direct)
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        reopened.load().join() shouldBe listOf(tx.after)
        Files.exists(receiptFile(root, tx)) shouldBe false
        val next = checkpointTransaction(tx.after, 2, 1)
        reopened.saveInventoryTransaction(next).join()
        reopened.loadInventoryTransactions().join() shouldBe listOf(next)
    }

    "an uncertain legacy head acknowledgement recovers the old accepted state without native replay" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        writeLegacyInventoryReceipt(root, tx)
        val store = CivsStore(root, direct)
        store.loadInventoryTransactions().join()
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

    "a queued checkpoint acknowledgement does not erase later accepted revisions" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val queue = ArrayDeque<Runnable>()
        val store = CivsStore(root, Executor { queue.addLast(it) })
        val first = city.copy(revision = 1)
        val second = city.copy(revision = 2)
        val third = city.copy(revision = 3)
        val one = store.stage(first)
        val checkpoint = store.flush()
        val two = store.stage(second)
        val three = store.stage(third)
        repeat(2) { queue.removeFirst().run() }
        one.join() shouldBe first
        checkpoint.join()
        CivsStore(root, direct).load().join() shouldBe listOf(first)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        two.join() shouldBe second; three.join() shouldBe third
        store.checkpointStats().dirtyCities shouldBe 1
        store.close()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        store.drained().join()
        CivsStore(root, direct).load().join() shouldBe listOf(third)
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

    "close drains an already submitted native completion without a Main callback" {
        val city = checkpointCity()
        CivsStore(root, direct).save(city).join()
        val queue = ArrayDeque<Runnable>()
        val store = CivsStore(root, Executor { queue.addLast(it) })
        val tx = checkpointTransaction(city, 3, 2)
        val admitted = store.saveInventoryTransaction(tx)
        val completed = store.completeInventoryTransaction(tx)
        store.close()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        admitted.join() shouldBe tx
        completed.join() shouldBe tx.after
        store.drained().join()
        CivsStore(root, direct).load().join() shouldBe listOf(tx.after)
        CivsStore(root, direct).loadInventoryTransactions().join() shouldBe emptyList()
    }

    "close reports unresolved RAM intent instead of claiming a successful clean drain" {
        val city = checkpointCity()
        val store = CivsStore(root, direct)
        store.save(city).join()
        val tx = checkpointTransaction(city, 3, 2)
        store.saveInventoryTransaction(tx).join()
        store.close()
        val failure = shouldThrow<CompletionException> { store.drained().join() }.cause as CivsCheckpointFailure
        failure.failures.keys shouldBe setOf(city.id)
        CivsStore(root, direct).load().join() shouldBe listOf(city)
        CivsStore(root, direct).loadInventoryTransactions().join() shouldBe emptyList()
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
    every { store.beginLocalInventory(any(), any()) } returns Unit
    every { store.completeLocalInventory(any()) } answers { firstArg<CivInventoryTransaction>().after }
    every { store.cancelLocalInventory(any()) } returns Unit
    every { store.hasLocalInventory(any()) } returns false
    every { store.completeInventoryTransaction(any()) } answers { store.save(firstArg<CivInventoryTransaction>().after) }
    every { store.stage(any()) } answers { store.save(firstArg()) }
    every { store.flush() } returns CompletableFuture.completedFuture(Unit)
    every { store.flushNext() } returns CompletableFuture.completedFuture(Unit)
    every { store.checkpointStats() } returns CivsCheckpointStats(0, CivsCheckpointReason.entries.associateWith { 0L })
}
