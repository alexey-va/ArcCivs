package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsConsumedItemsTest : FunSpec({
    lateinit var root: Path
    beforeEach { root = Files.createTempDirectory("civs-consumed-") }
    afterEach { root.toFile().deleteRecursively() }

    test("compact immutable membership preserves unsigned prefixes and signed UUID ordering") {
        val ids = listOf(UUID(Long.MIN_VALUE, -1), UUID(Long.MIN_VALUE, 0), UUID(-1, -1), UUID(0, 0), UUID(0, -1), UUID(Long.MAX_VALUE, 1))
        val first = CivConsumedItemIndex.EMPTY.adding(ids)
        first.size shouldBe ids.size
        ids.forEach { (it in first) shouldBe true }
        (UUID(0, 1) in first) shouldBe false
        val later = first.adding(listOf(ids[0], UUID(0, 1)))
        later.size shouldBe ids.size + 1
        (UUID(0, 1) in first) shouldBe false
        (UUID(0, 1) in later) shouldBe true
    }

    test("atomic history reload is idempotent across worlds and does not expire") {
        val ids = listOf(UUID(0, 1), UUID(Long.MIN_VALUE, 4), UUID(-1, 9))
        val world = UUID.randomUUID()
        val history = CivsConsumedItems()
        history.mark(root, world, ids).size shouldBe 3
        history.mark(root, UUID.randomUUID(), ids).size shouldBe 3
        val reopened = CivsConsumedItems().load(root)
        ids.forEach { (it in reopened) shouldBe true }
        Files.list(root.resolve("consumed-ground")).use { it.count() } shouldBe 1L
    }

    test("corrupt checksum unexpected paths and symlink buckets fail closed") {
        val world = UUID.randomUUID()
        CivsConsumedItems().mark(root, world, listOf(UUID(0, 1)))
        val file = root.resolve("consumed-ground/$world/00.bin")
        val original = Files.readAllBytes(file)
        Files.write(file, original.copyOf().apply { this[15] = (this[15].toInt() xor 1).toByte() })
        shouldThrow<IllegalArgumentException> { CivsConsumedItems().load(root) }
        Files.write(file, original)
        val stray = file.resolveSibling("unexpected.json")
        Files.writeString(stray, "{}")
        shouldThrow<IllegalArgumentException> { CivsConsumedItems().load(root) }
        Files.delete(stray)
        val target = root.resolve("outside.bin")
        Files.move(file, target)
        Files.createSymbolicLink(file, target)
        shouldThrow<IllegalArgumentException> { CivsConsumedItems().load(root) }
    }

    test("idempotent marker acknowledgement cannot bypass a replaced history directory") {
        val world = UUID.randomUUID()
        val id = UUID(0, 1)
        val history = CivsConsumedItems()
        history.mark(root, world, listOf(id))
        val path = root.resolve("consumed-ground/$world")
        val moved = root.resolve("moved-history")
        Files.move(path, moved)
        Files.createSymbolicLink(path, moved)
        shouldThrow<IllegalArgumentException> { history.mark(root, world, listOf(id)) }
    }

    test("prefix capacity reserves pending receipt UUIDs before another journal can be written") {
        val first = consumedReceipt(UUID(0, CivConsumedItemIndex.MAX_BUCKET_ITEMS.toLong()))
        val second = consumedReceipt(UUID(0, CivConsumedItemIndex.MAX_BUCKET_ITEMS.toLong() + 1))
        CivsConsumedItems().mark(root, first.after.worldId,
            (1 until CivConsumedItemIndex.MAX_BUCKET_ITEMS).map { UUID(0, it.toLong()) })
        val store = CivsStore(root, Executor(Runnable::run))
        store.saveInventoryTransaction(first).join() shouldBe first
        val failure = shouldThrow<CompletionException> { store.saveInventoryTransaction(second).join() }
        CivsDiagnostics.rejection(failure)?.reason shouldBe "ground-history-full"
        store.loadInventoryTransactions().join() shouldBe listOf(first)
        // The accepted reservation can still be completed at capacity and retried without consuming another slot.
        store.markConsumedItems(first).join().size shouldBe CivConsumedItemIndex.MAX_BUCKET_ITEMS
        store.saveInventoryTransaction(first).join() shouldBe first
        store.markConsumedItems(first).join().size shouldBe CivConsumedItemIndex.MAX_BUCKET_ITEMS
        store.close()
    }

    test("marker needs its exact durable receipt and remains after that receipt retires") {
        val receipt = consumedReceipt(UUID.randomUUID())
        val store = CivsStore(root, Executor(Runnable::run))
        shouldThrow<CompletionException> { store.markConsumedItems(receipt).join() }
        store.loadConsumedItems().join().size shouldBe 0
        store.saveInventoryTransaction(receipt).join()
        store.markConsumedItems(receipt).join().size shouldBe 1
        store.deleteInventoryTransaction(receipt.id).join() shouldBe true
        val reopened = CivsStore(root, Executor(Runnable::run))
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        val id = receipt.collected!!.ground.single().entityId
        (id in reopened.loadConsumedItems().join()) shouldBe true
        val duplicate = consumedReceipt(id)
        val failure = shouldThrow<CompletionException> { reopened.saveInventoryTransaction(duplicate).join() }
        CivsDiagnostics.rejection(failure)?.reason shouldBe "ground-item-consumed"
        reopened.loadInventoryTransactions().join() shouldBe emptyList()
        store.close(); reopened.close()
    }
})

private fun consumedReceipt(entityId: UUID): CivInventoryTransaction {
    val at = CivPosition(0, 64, 0)
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, at, 0, "test",
        CivBox(CivPosition(-2, 63, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -2), BuildingPhase.ACTIVE)
    val worker = CivWorker(UUID.randomUUID(), "Resident", null)
    val city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "History", UUID.randomUUID(), at,
        16, "nordic", Stock(), listOf(hall), listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL)))
    val next = worker.copy(items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COAL", 1) else null },
        hasCollectedItems = true)
    return CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
        city.copy(workers = listOf(next), revision = city.revision + 1), emptyList(),
        collected = CivCollectedTransfer(worker.id, CivsEquipment.slots(worker), ground = listOf(
            CivGroundItemChange(entityId, CivPosition(1, 64, 0), CivResidentItem("COAL", 1), null)))).validated()
}
