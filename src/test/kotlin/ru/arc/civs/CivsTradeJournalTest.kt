package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsTradeJournalTest : FunSpec({
    lateinit var h: TradeJournalFixture
    beforeEach { h = TradeJournalFixture() }
    afterEach { h.close() }

    test("trade fences native preimages and credits before it publishes or retires the accepted receipt") {
        val source = CompletableFuture<Unit>()
        val after = CompletableFuture<Unit>()
        val fences = mutableListOf<Boolean>()
        h.persistence = { _, before -> fences += before; if (before) source else after }
        val before = h.city
        val tx = h.prepare()
        val result = h.journal.submitTrade(before, tx) {
            (before.id in h.busy) shouldBe true
            h.storage.hasClaim(before) shouldBe true
            CivsTrade.checkAccess(before, h.player)
        }
        (before.id in h.busy) shouldBe true
        h.playerSaves.single().first() shouldBe ItemStack(Material.EMERALD, 1)
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 64)
        source.complete(Unit); h.ticks()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.city shouldBe before
        result.isDone shouldBe false
        h.playerSaves.last().first() shouldBe ItemStack(Material.COBBLESTONE, 32)
        fences shouldBe listOf(true, false)
        verify(exactly = 0) { h.store.save(any()) }
        verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        after.complete(Unit); h.ticks()
        result.join() shouldBe tx.after
        h.storage.hasClaim(tx.after) shouldBe false
        h.journal.recover(); h.ticks()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("leaving the public market during its source fence cancels an untouched trade before journal admission") {
        val source = CompletableFuture<Unit>()
        h.persistence = { _, before -> if (before) source else CompletableFuture.completedFuture(Unit) }
        val before = h.city
        val tx = h.prepare()
        val result = h.journal.submitTrade(before, tx) { CivsTrade.checkAccess(before, h.player) }
        h.player.teleport(Location(h.world, 40.0, 64.0, 3.0))
        source.complete(Unit); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.EMERALD, 1)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 64)
        h.city shouldBe before
        h.storage.hasClaim(before) shouldBe false
        h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
    }

    test("an unknown native save acknowledgement recovers the durable accepted trade without a new offer or payout") {
        val after = CompletableFuture<Unit>()
        h.persistence = { _, before -> if (before) CompletableFuture.completedFuture(Unit) else after }
        val before = h.city
        val tx = h.prepare()
        val result = h.journal.submitTrade(before, tx) { CivsTrade.checkAccess(before, h.player) }
        h.ticks()
        after.completeExceptionally(CivsNativePersistenceRetry("trade region acknowledgement unknown")); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.journal.pendingCount shouldBe 1
        h.storage.hasClaim(before) shouldBe true
        val root = Files.createTempDirectory("trade-recovery-")
        val stored = try {
            CivsStore(root, Executor { it.run() }).use { store ->
                store.saveInventoryTransaction(tx).join() shouldBe tx
                store.loadInventoryTransactions().join().single()
            }
        } finally { root.toFile().deleteRecursively() }
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(stored))
        h.persistence = { _, _ -> CompletableFuture.completedFuture(Unit) }
        h.player.teleport(Location(h.world, 40.0, 64.0, 3.0))
        val bootstrap = h.journal.bootstrap(); h.ticks(); bootstrap.join()
        h.time += 5_000
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.storageContents.filterNotNull().filter { it.type == Material.EMERALD }.sumOf { it.amount } shouldBe 1
        h.journal.recover(); h.ticks()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("disconnecting during the native credit fence waits for rejoin without permanently blocking recovery") {
        val after = CompletableFuture<Unit>()
        h.persistence = { _, before -> if (before) CompletableFuture.completedFuture(Unit) else after }
        val before = h.city
        val tx = h.prepare()
        val result = h.journal.submitTrade(before, tx)
        h.ticks()
        h.player.disconnect()
        after.complete(Unit); h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.journal.pendingCount shouldBe 1
        h.journal.blockedCount shouldBe 0
        h.time += 5_000
        h.journal.recover(); h.ticks()
        h.city shouldBe before
        h.player.reconnect()
        h.persistence = { _, _ -> CompletableFuture.completedFuture(Unit) }
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
    }
})

private class TradeJournalFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = paper.addSimpleWorld("trade-journal")
    private val plugin = paper.createSimplePlugin("TradeJournal")
    private val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val player = paper.addPlayer("TradeVisitor")
    val playerSaves = mutableListOf<List<ItemStack?>>()
    val storage = CivsStorage(plugin, playerDataPersistence = PaperPlayerDataPersistence { player ->
        playerSaves += player.inventory.storageContents.map { it?.clone() }
    }) { emptyArray() }
    val barrel get() = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
    val store = mockk<CivsStore>()
    private val cities = mutableMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value }
    val busy = mutableSetOf<UUID>()
    private val unhealthy = mutableSetOf<UUID>()
    var time = 10_000L
    var persistence: (CivInventoryTransaction, Boolean) -> CompletableFuture<Unit> = { _, _ -> CompletableFuture.completedFuture(Unit) }
    val journal: CivsInventoryJournal
    init {
        world.loadChunk(0, 0)
        player.addAttachment(plugin, "arccivs.use", true)
        player.teleport(Location(world, 3.0, 64.0, 3.0))
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        barrel.setItem(0, ItemStack(Material.COBBLESTONE, 64))
        player.inventory.setItem(0, ItemStack(Material.EMERALD, 1))
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
            "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3),
            BuildingPhase.ACTIVE, control = CivPosition(0, 64, 0))
        city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Trade", world.uid,
            CivPosition(0, 64, 0), 16, "nordic", hall.copy(phase = BuildingPhase.SITE), 1_000).copy(
            buildings = listOf(hall), inventory = CivInventory(CivStorageMode.PHYSICAL))
        storage.persistCollectedNative = { tx, before -> persistence(tx, before) }
        every { store.saveInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("Unexpected trade city mutation") }, {}, { error("No collection receipt") },
            { _, _, _ -> null }, {}, { tx, _ -> cities.getValue(tx.cityId) }, {}, { time })
    }
    fun prepare(): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserveTrade(city, id, player)
        val plan = storage.prepareTrade(city, player, CivTradeOffer("COBBLESTONE", CivTradeDirection.BUY, 32))
        return CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1),
            plan.changes, trade = plan.trade).also(storage::seal)
    }
    fun ticks() { paper.performTicks(8) }
    override fun close() { tasks.close(); storage.close(); paper.close() }
}
