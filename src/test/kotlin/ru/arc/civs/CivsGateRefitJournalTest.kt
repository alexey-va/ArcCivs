package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import net.kyori.adventure.text.Component
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsGateRefitJournalTest : FunSpec({
    test("stepped gate publishes its route detour only with the verified geometry and preserves native goods") {
        GateJournalFixture(variant = true).use { h ->
            h.captureFence = CompletableFuture()
            h.prepare()
            val result = h.journal.submit(h.f.transaction)
            h.ticks()
            h.city.roadRouteOverrides shouldBe h.f.before.roadRouteOverrides
            h.states() shouldBe h.beforeStates()
            result.isDone shouldBe false
            h.captureFence.complete(Unit)
            h.await(result) shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.goods() shouldBe h.originalGoods
            h.city.stock shouldBe h.f.before.stock
            h.city.inventory shouldBe h.f.before.inventory
            h.f.receipt.before(h.city) shouldBe h.f.before
        }
    }

    test("mixed stepped gate recovery keeps the exact saved detour before and after city publication") {
        for (published in listOf(false, true)) GateJournalFixture(variant = true).use { h ->
            if (published) { h.city = h.f.after; h.storage.updateCity(h.city) }
            h.f.receipt.changes().take(17).forEach { h.put(it.position, it.after) }
            h.await(h.journal.bootstrap())
            h.journal.recover(); h.ticks()
            h.city shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.goods() shouldBe h.originalGoods
            h.journal.pendingCount shouldBe 0
            h.journal.recover(); h.ticks()
            // Recovery republishes the exact saved after-state once, then retires the intent.
            // A second recover must neither increment its revision nor publish again.
            h.publications shouldBe 1
            h.city.revision shouldBe h.f.after.revision
            verify(exactly = 1) { h.store.deleteInventoryTransaction(h.f.transaction.id) }
        }
    }

    test("an unsupported KEEP_NATIVE gate floor defers the whole accepted refit without replacing the floor") {
        GateJournalFixture(variant = true).use { h ->
            val captured = h.f.source.blocks.map { it.position }.toSet()
            val floor = h.f.receipt.routeOverrides!!.values.single().gateDetours!!.single().after
                .map { it.copy(y = it.y - 1) }.first { it !in captured }
            h.put(floor, "minecraft:air")
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe h.beforeStates()
            h.world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.AIR
            h.city shouldBe h.f.before
            h.journal.blockedCount shouldBe 0
            h.journal.pendingCount shouldBe 1
            h.goods() shouldBe h.originalGoods
            h.put(floor, "minecraft:cobblestone")
            h.journal.recover(); h.ticks()
            h.city shouldBe h.f.after
            h.states() shouldBe h.afterStates()
        }
    }

    test("no gate block changes before the durable intent and both capture verifications complete") {
        GateJournalFixture().use { h ->
            h.journalWrite = CompletableFuture()
            h.captureFence = CompletableFuture()
            h.prepare()
            val result = h.journal.submit(h.f.transaction)
            h.states() shouldBe h.beforeStates()
            h.verifications shouldBe 0
            h.journalWrite!!.complete(h.f.transaction); h.ticks()
            h.verifications shouldBe 1
            h.states() shouldBe h.beforeStates()
            result.isDone shouldBe false
            h.captureFence.complete(Unit)
            h.await(result) shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.city.stock shouldBe h.f.before.stock
            h.city.workers shouldBe h.f.before.workers
            h.publications shouldBe 1
            h.journal.pendingCount shouldBe 0
        }
    }

    test("restart replays the same mixed gate receipt and preserves the paid city state exactly once") {
        GateJournalFixture().use { h ->
            h.f.receipt.changes().take(17).forEach { h.put(it.position, it.after) }
            h.await(h.journal.bootstrap())
            h.journal.pendingCount shouldBe 1
            h.journal.recover(); h.ticks()
            h.states() shouldBe h.afterStates()
            h.city shouldBe h.f.after
            h.journal.pendingCount shouldBe 0
            h.journal.recover(); h.ticks()
            h.publications shouldBe 1
            verify(exactly = 1) { h.store.deleteInventoryTransaction(h.f.transaction.id) }
        }
    }

    test("a saved after-city still reconciles its retained pending gate receipt before retirement") {
        GateJournalFixture().use { h ->
            h.city = h.f.after
            h.storage.replaceCities(listOf(h.city))
            h.f.receipt.changes().take(8).forEach { h.put(it.position, it.after) }
            h.await(h.journal.bootstrap())
            h.journal.recover(); h.ticks()
            h.city shouldBe h.f.after
            h.states() shouldBe h.afterStates()
            h.journal.pendingCount shouldBe 0
        }
    }

    test("a third native block state prevents every otherwise valid gate write") {
        GateJournalFixture().use { h ->
            val changed = h.f.receipt.changes().first()
            h.put(changed.position, "minecraft:gold_block")
            val actualBefore = h.states()
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe actualBefore
            h.city shouldBe h.f.before
            h.journal.blockedCount shouldBe 1
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("capture verification failure retains intent without native writes or a reference swap") {
        GateJournalFixture().use { h ->
            h.captureFence = CompletableFuture.failedFuture(IllegalStateException("Missing immutable capture"))
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe h.beforeStates()
            h.city shouldBe h.f.before
            h.journal.blockedCount shouldBe 1
            verify(exactly = 0) { h.store.save(any()) }
            verify(exactly = 0) { h.store.deleteInventoryTransaction(any()) }
        }
    }

    test("an occupied support retries the same receipt after the resident leaves") {
        GateJournalFixture().use { h ->
            h.occupied = true
            h.prepare()
            shouldThrow<CompletionException> { h.await(h.journal.submit(h.f.transaction)) }
            h.states() shouldBe h.beforeStates()
            h.journal.blockedCount shouldBe 0
            h.journal.pendingCount shouldBe 1
            h.occupied = false
            h.journal.recover(); h.ticks()
            h.states() shouldBe h.afterStates()
            h.city shouldBe h.f.after
            h.publications shouldBe 1
        }
    }
})

private class GateJournalFixture(variant: Boolean = false) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = CivsWorksiteWorldFixture("gate-refit").also(paper.server::addWorld)
    val plugin = paper.createSimplePlugin("GateRefitTest")
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val f = if (variant) gateVariantFixture(reroute = true, worldId = world.uid) else gateRefitFixture(worldId = world.uid)
    private val cities = mutableMapOf(f.before.id to f.before)
    var city: Settlement
        get() = cities.getValue(f.before.id)
        set(value) { cities[value.id] = value }
    val busy = mutableSetOf<UUID>()
    val unhealthy = mutableSetOf<UUID>()
    val store = mockk<CivsStore>()
    val storage = CivsStorage(plugin) { emptyArray() }
    var journalWrite: CompletableFuture<CivInventoryTransaction>? = null
    var captureFence = CompletableFuture.completedFuture(Unit)
    var verifications = 0
    var publications = 0
    var occupied = false
    val journal: CivsInventoryJournal
    private val maintenance: CivsGateMaintenance
    private val goodsPosition = CivPosition(8, 64, 8)
    val originalGoods = ItemStack(Material.DIAMOND, 7).apply {
        itemMeta = itemMeta.apply { displayName(Component.text("Native goods outside the gate")) }
    }

    init {
        world.loadChunk(1, 1)
        world.loadChunk(1, 0)
        world.loadChunk(0, 0)
        if (variant) (f.gate.transit.orEmpty() + f.before.roadRouteOverrides.orEmpty().values.flatMap { it.route }).forEach { feet ->
            put(feet.copy(y = feet.y - 1), "minecraft:cobblestone")
        }
        f.source.blocks.forEach { put(it.position, it.after) }
        world.getBlockAt(goodsPosition.x, goodsPosition.y, goodsPosition.z).type = Material.BARREL
        (world.getBlockAt(goodsPosition.x, goodsPosition.y, goodsPosition.z).state as Barrel).inventory.setItem(0, originalGoods)
        storage.replaceCities(listOf(city))
        every { store.saveInventoryTransaction(any()) } answers { journalWrite ?: CompletableFuture.completedFuture(firstArg()) }
        every { store.save(any()) } answers { CompletableFuture.completedFuture(firstArg()) }
        every { store.deleteInventoryTransaction(any()) } returns CompletableFuture.completedFuture(true)
        every { store.loadInventoryTransactions() } returns CompletableFuture.completedFuture(listOf(f.transaction))
        every { store.loadConsumedItems() } returns CompletableFuture.completedFuture(CivConsumedItemIndex.EMPTY)
        journal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy, unhealthy, { false },
            { saved -> city = saved; publications++; storage.updateCity(saved); CompletableFuture.completedFuture(Unit) },
            { _, _ -> error("Gate maintenance must not create a second city mutation") }, {}, {}, { _, _, _ -> null }, {},
            { _, _ -> city }, {}, { 0L }, verifyGateCaptures = { verifications++; captureFence })
        val nativeWorld = mockk<CivsWorld> {
            every { canModify(any(), any(), any<Material>()) } returns true
        }
        val records = CivsCityRecords({ cities }, busy, unhealthy, { false }, { false }, { null }, { 0 }, { 0 })
        maintenance = CivsGateMaintenance(plugin, mockk(relaxed = true), records, tasks, Executor { it.run() },
            store, mockk(), nativeWorld, storage, journal, { CompletableFuture.completedFuture(listOf(f.source)) },
            { false }, { _, _ -> occupied })
        storage.validateGateRefitNative = maintenance::validateNative
    }

    fun ticks() { paper.performTicks(8) }
    fun <T> await(future: CompletableFuture<T>): T { ticks(); future.isDone shouldBe true; return future.join() }
    fun prepare() { storage.reserve(city, f.transaction.id); storage.seal(f.transaction) }
    fun put(p: CivPosition, state: String) = world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(state), false)
    fun states() = f.receipt.replacement.blocks.associate { cell ->
        cell.position to world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString
    }
    fun beforeStates() = f.receipt.replacement.blocks.associate { it.position to it.before }
    fun afterStates() = f.receipt.replacement.blocks.associate { it.position to it.after }
    fun goods() = (world.getBlockAt(goodsPosition.x, goodsPosition.y, goodsPosition.z).state as Barrel).inventory.getItem(0)

    override fun close() { storage.close(); tasks.close(); paper.close() }
}
