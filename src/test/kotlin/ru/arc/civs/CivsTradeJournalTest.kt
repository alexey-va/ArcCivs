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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsTradeJournalTest : FunSpec({
    lateinit var h: TradeJournalFixture
    beforeEach { h = TradeJournalFixture() }
    afterEach { h.close() }

    test("new trade keeps native locks until publication without player or container persistence") {
        h.persistence = { _, _ -> error("A new trade must not force native persistence") }
        val published = CompletableFuture<Unit>()
        h.publication = published
        val before = h.city
        val tx = h.prepare()
        val result = h.journal.submitTrade(before, tx) {
            (before.id in h.busy) shouldBe true
            h.storage.hasClaim(before) shouldBe true
            CivsTrade.checkAccess(before, h.player)
        }
        h.ticks()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.playerSaves shouldBe emptyList()
        result.isDone shouldBe false
        h.storage.hasClaim(before) shouldBe true
        verify(exactly = 0) { h.store.save(any()) }
        verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        published.complete(Unit); h.ticks()
        result.join() shouldBe tx.after
        h.storage.hasClaim(before) shouldBe false
        h.journal.pendingCount shouldBe 0
        h.journal.recover(); h.ticks()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
        verify(exactly = 1) { h.store.deleteInventoryTransaction(tx.id) }
    }

    test("native after-state submits RAM completion in the same Main turn before a shutdown can drain") {
        h.persistence = { _, _ -> error("No native persistence") }
        val completion = CompletableFuture<Settlement>()
        val before = h.city
        val tx = h.prepare()
        every { h.store.completeInventoryTransaction(tx) } returns completion
        h.journal.submitTrade(before, tx)
        repeat(12) {
            if (h.player.inventory.getItem(0)?.type == Material.EMERALD) h.oneTick()
        }
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        // No next-tick persistence callback separates the native writes from this storage-lane admission.
        verify(exactly = 1) { h.store.completeInventoryTransaction(tx) }
        h.storage.hasClaim(before) shouldBe true
        completion.complete(tx.after); h.ticks()
        h.storage.hasClaim(tx.after) shouldBe false
    }

    test("known stale RAM admission releases locks and does not create a false recovery intent") {
        val before = h.city
        val tx = h.prepare()
        every { h.store.saveInventoryTransaction(tx) } returns CompletableFuture.failedFuture(CivsRejection("stale-city"))
        val result = h.journal.submitTrade(before, tx)
        h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.journal.pendingCount shouldBe 0
        h.storage.hasClaim(before) shouldBe false
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.EMERALD, 1)
        val next = h.prepare()
        val accepted = h.journal.submitTrade(before, next)
        h.ticks()
        accepted.join() shouldBe next.after
    }

    test("expired fresh trade authority cancels before RAM admission or native mutation") {
        h.persistence = { _, _ -> error("No native persistence") }
        val before = h.city
        val tx = h.prepare()
        h.player.teleport(Location(h.world, 40.0, 64.0, 3.0))
        val result = h.journal.submitTrade(before, tx) { CivsTrade.checkAccess(before, h.player) }
        h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.EMERALD, 1)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 64)
        h.playerSaves shouldBe emptyList()
        h.city shouldBe before
        h.storage.hasClaim(before) shouldBe false
        h.journal.pendingCount shouldBe 0
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
    }

    test("failure after native mutation retains the RAM intent and reload retries without double credit or persistence") {
        h.persistence = { _, _ -> error("A same-process retry must stay in RAM mode") }
        val before = h.city
        val tx = h.prepare()
        every { h.store.completeInventoryTransaction(tx) } returns CompletableFuture.failedFuture(IllegalStateException("RAM lane rejected completion"))
        val result = h.journal.submitTrade(before, tx)
        h.ticks()
        shouldThrow<CompletionException> { result.join() }
        h.city shouldBe before
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.storage.hasClaim(before) shouldBe true
        h.journal.pendingCount shouldBe 1
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
        every { h.store.completeInventoryTransaction(tx) } returns CompletableFuture.completedFuture(tx.after)
        val bootstrap = h.journal.bootstrap(); h.ticks(); bootstrap.join()
        h.journal.recover(); h.ticks()
        h.city shouldBe tx.after
        h.storage.hasClaim(tx.after) shouldBe false
        h.journal.pendingCount shouldBe 0
        h.playerSaves shouldBe emptyList()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.storageContents.filterNotNull().filter { it.type == Material.EMERALD }.sumOf { it.amount } shouldBe 1
        verify(exactly = 1) { h.store.saveInventoryTransaction(tx) }
    }

    test("a legacy disk trade keeps its native persistence fence until recovery finishes") {
        val after = CompletableFuture<Unit>()
        val fences = mutableListOf<Boolean>()
        h.persistence = { _, before -> fences += before; after }
        val before = h.city
        val tx = h.prepare()
        every { h.store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(tx))
        val bootstrap = h.journal.bootstrap(); h.ticks(); bootstrap.join()
        h.player.teleport(Location(h.world, 40.0, 64.0, 3.0))
        h.journal.recover(); h.ticks()
        h.city shouldBe before
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.storage.hasClaim(before) shouldBe true
        fences shouldBe listOf(false)
        h.playerSaves.size shouldBe 1
        after.complete(Unit); h.ticks()
        h.city shouldBe tx.after
        h.storage.hasClaim(tx.after) shouldBe false
        h.journal.pendingCount shouldBe 0
        h.journal.recover(); h.ticks()
        h.player.inventory.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        h.barrel.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 32)
        verify(exactly = 0) { h.store.saveInventoryTransaction(any()) }
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
    val store = mockk<CivsStore>().also { it.stubCheckpointAcknowledgements() }
    private val cities = mutableMapOf<UUID, Settlement>()
    var city: Settlement
        get() = cities.values.single()
        set(value) { cities[value.id] = value }
    val busy = mutableSetOf<UUID>()
    private val unhealthy = mutableSetOf<UUID>()
    var time = 10_000L
    var publication: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
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
        every { store.completeInventoryTransaction(any()) } answers { CompletableFuture.completedFuture(firstArg<CivInventoryTransaction>().after) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(emptyList())
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; publication },
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
    fun oneTick() { paper.performTicks(1) }
    fun ticks() { paper.performTicks(8) }
    override fun close() { tasks.close(); storage.close(); paper.close() }
}
