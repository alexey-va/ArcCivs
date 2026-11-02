package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsWarehouseRefitJournalTest : FunSpec({
    test("capture verification and durable intent precede all shell writes and metadata publication") {
        WarehouseJournalFixture().use { h ->
            h.captureFence = CompletableFuture()
            h.prepare()
            val result = h.journal.submit(h.f.transaction)
            h.ticks()
            result.isDone shouldBe false
            h.city shouldBe h.f.before
            h.states() shouldBe h.beforeStates()
            h.goods() shouldBe h.originalGoods
            h.captureFence.complete(Unit)
            h.await(result) shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.goods() shouldBe h.originalGoods
            h.city.stock shouldBe h.f.before.stock
            h.city.workers shouldBe h.f.before.workers
            h.city.construction shouldBe h.f.before.construction
        }
    }

    test("mixed restart before or after city publication resumes the exact shell and preserves all351 native slots") {
        for (published in listOf(false, true)) WarehouseJournalFixture().use { h ->
            if (published) { h.city = h.f.after; h.storage.updateCity(h.city) }
            h.f.receipt.changes().take(117).forEach { h.put(it.position, it.after) }
            h.await(h.journal.bootstrap()); h.journal.recover(); h.ticks()
            h.city shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.goods() shouldBe h.originalGoods
            h.originalGoods.size shouldBe 13
            h.originalGoods.values.all { it.size == 27 && it.all { slot -> slot != null } } shouldBe true
            h.journal.pendingCount shouldBe 0
            h.journal.recover(); h.ticks()
            h.publications shouldBe 1
            h.city.revision shouldBe h.f.after.revision
            verify(exactly = 1) { h.store.deleteInventoryTransaction(h.f.transaction.id) }
        }
    }

    test("inventory drift blocks the entire accepted repair without overwriting or restoring any slot") {
        WarehouseJournalFixture().use { h ->
            val holder = h.f.receipt.containers.last()
            h.inventory(holder.position).setItem(26, ItemStack(Material.GOLD_INGOT, 17))
            val expected = h.goods()
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe h.beforeStates()
            h.goods() shouldBe expected
            h.city shouldBe h.f.before
            h.journal.blockedCount shouldBe 1
        }
    }

    test("floor and lid drift stop admission and never replace a native container or block") {
        for (lid in listOf(false, true)) WarehouseJournalFixture().use { h ->
            val point = if (lid) h.f.receipt.containers.first { it.type == "CHEST" }.position.let { it.copy(y = it.y + 1) }
                else h.f.receipt.changes().first { it.position.y == h.f.building.bounds.min.y + 1 }.position.let { it.copy(y = it.y - 1) }
            h.put(point, if (lid) "minecraft:stone" else "minecraft:air")
            val states = h.states()
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe states
            h.goods() shouldBe h.originalGoods
            h.city shouldBe h.f.before
        }
    }

    test("a real occupied shell cell retains the same receipt and retries after ordinary departure") {
        WarehouseJournalFixture().use { h ->
            h.occupied = true
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe h.beforeStates()
            h.journal.pendingCount shouldBe 1
            h.journal.blockedCount shouldBe 0
            h.occupied = false
            h.journal.recover(); h.ticks()
            h.city shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.goods() shouldBe h.originalGoods
        }
    }

    test("missing or altered immutable capture bytes prevent native replay") {
        WarehouseJournalFixture().use { h ->
            h.captureFence = CompletableFuture.failedFuture(IllegalStateException("Captured digest changed"))
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe h.beforeStates()
            h.goods() shouldBe h.originalGoods
            h.city shouldBe h.f.before
        }
    }
})

private class WarehouseJournalFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = CivsWorksiteWorldFixture("warehouse-refit").also(paper.server::addWorld)
    val plugin = paper.createSimplePlugin("WarehouseRefitTest")
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val slots = List(27) { index -> ItemStack(if (index % 2 == 0) Material.DIAMOND else Material.IRON_INGOT, index + 1).apply {
        itemMeta = itemMeta.apply { displayName(Component.text("Retained warehouse slot $index")) }
    } }
    val f = warehouseRefitFixture(worldId = world.uid, items = slots.map(CivResidentItem::of))
    private val cities = mutableMapOf(f.before.id to f.before)
    var city: Settlement
        get() = cities.getValue(f.before.id)
        set(value) { cities[value.id] = value }
    val busy = mutableSetOf<UUID>()
    val unhealthy = mutableSetOf<UUID>()
    val store = mockk<CivsStore>()
    val storage = CivsStorage(plugin) { chunk -> f.receipt.containers.filter {
        it.position.x shr 4 == chunk.x && it.position.z shr 4 == chunk.z
    }.map { world.getBlockAt(it.position.x, it.position.y, it.position.z).state }.toTypedArray() }
    var captureFence = CompletableFuture.completedFuture(Unit)
    var publications = 0
    var occupied = false
    val journal: CivsInventoryJournal
    private val maintenance: CivsWarehouseMaintenance
    val originalGoods: Map<CivPosition, List<CivResidentItem?>>

    init {
        for (x in -1..4) for (z in -1..4) world.loadChunk(x, z)
        f.receipt.beforeCells.forEach { put(it.position, it.before) }
        f.receipt.routes.flatten().forEach { p -> put(p.copy(y = p.y - 1), "minecraft:cobblestone") }
        f.receipt.containers.forEach { holder -> slots.forEachIndexed { slot, item -> inventory(holder.position).setItem(slot, item.clone()) } }
        originalGoods = goods()
        storage.replaceCities(listOf(city))
        every { store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(f.transaction))
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; publications++; storage.updateCity(saved); CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("Warehouse repair must use the existing receipt only") }, {}, {}, { _, _, _ -> null }, {},
            { _, _ -> city }, {}, { 0L }, verifyWarehouseCaptures = { captureFence })
        val nativeWorld = mockk<CivsWorld> { every { canModify(any(), any(), any<Material>()) } returns true }
        val records = CivsCityRecords({ cities }, busy, unhealthy, { false }, { false }, { null }, { 0 }, { 0 })
        maintenance = CivsWarehouseMaintenance(plugin, mockk(relaxed = true), records, tasks, Executor { it.run() },
            store, mockk(), nativeWorld, storage, journal, { CompletableFuture.completedFuture(listOf(f.source)) },
            { false }, { _, _ -> occupied })
        storage.validateWarehouseRefitNative = maintenance::validateNative
    }

    fun inventory(p: CivPosition) = when (val state = world.getBlockAt(p.x, p.y, p.z).state) {
        is Chest -> state.blockInventory
        is Barrel -> state.inventory
        else -> error("Expected a native warehouse holder")
    }
    fun ticks() { paper.performTicks(12) }
    fun <T> await(future: CompletableFuture<T>): T { ticks(); future.isDone shouldBe true; return future.join() }
    fun prepare() { storage.reserve(city, f.transaction.id); storage.seal(f.transaction) }
    fun put(p: CivPosition, state: String) {
        val block = world.getBlockAt(p.x, p.y, p.z)
        val data = Bukkit.createBlockData(state)
        if (block.type != data.material) block.type = data.material
        block.setBlockData(data, false)
    }
    fun states() = f.receipt.beforeCells.associate { it.position to world.getBlockAt(it.position.x, it.position.y, it.position.z).blockData.asString }
    fun beforeStates() = f.receipt.beforeCells.associate { it.position to it.before }
    fun afterStates() = beforeStates() + f.receipt.replacement.blocks.associate { it.position to it.after }
    fun goods() = f.receipt.containers.associate { it.position to inventory(it.position).storageContents.map(CivResidentItem::of) }
    override fun close() { storage.close(); tasks.close(); paper.close() }
}
