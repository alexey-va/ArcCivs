package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsControlRelocationTest : FunSpec({
    test("same-level evacuation preserves exact native metadata in existing holders and never writes an uncreated control") {
        ControlRelocationFixture().use { h ->
            val named = ItemStack(Material.DIAMOND, 7).apply { itemMeta = itemMeta.apply { displayName(Component.text("Keep exact metadata")) } }
            h.oldInventory.setItem(0, named)
            h.receiverInventory.setItem(0, ItemStack(Material.EMERALD, 5))
            val tx = h.prepare()
            h.block(h.replacement).type shouldBe Material.AIR
            h.oldInventory.getItem(0) shouldBe named
            tx.containerRelocation!!.containers.map { it.position }.toSet() shouldBe setOf(h.old, h.receiver)
            h.storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
            h.oldInventory.getItem(0) shouldBe null
            h.receiverInventory.contents.filterNotNull().single { it.type == Material.DIAMOND } shouldBe named
            h.receiverInventory.getItem(0) shouldBe ItemStack(Material.EMERALD, 5)
            h.block(h.replacement).type shouldBe Material.AIR
            // Replaying the same accepted exact receipt cannot add a second copy.
            h.storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
            h.receiverInventory.contents.filterNotNull().filter { it.type == Material.DIAMOND }.sumOf { it.amount } shouldBe 7
            h.storage.unlock(tx.id)
        }
    }

    test("a full existing receiver leaves every source item and native block untouched") {
        ControlRelocationFixture().use { h ->
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            repeat(STORAGE_SLOTS) { h.receiverInventory.setItem(it, ItemStack(Material.STONE, 64)) }
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            shouldThrow<CivsRejection> { h.storage.prepareControlRelocation(h.city, h.building, h.old, h.replacement) }.reason shouldBe "storage-full"
            h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.receiverInventory.contents.filterNotNull().sumOf { it.amount } shouldBe STORAGE_SLOTS * 64
            h.block(h.replacement).type shouldBe Material.AIR
            h.storage.unlock(id)
        }
    }

    test("pending retirement protects both native blocks and coalesces the native persistence barrier") {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            val player = h.paper.addPlayer("ControlObserver")
            val oldBreak = BlockBreakEvent(h.block(h.old), player)
            h.storage.onBreak(oldBreak)
            oldBreak.isCancelled shouldBe true
            val first = h.installations.install(h.city.id, h.building.id)
            val second = h.installations.install(h.city.id, h.building.id)
            (first === second) shouldBe true
            h.barriers shouldBe 1
            h.mutations shouldBe 0
            h.block(h.old).type shouldBe Material.AIR
            h.block(h.replacement).type shouldBe Material.BARREL
            h.city.pendingControls!!.size shouldBe 1
            val replacementBreak = BlockBreakEvent(h.block(h.replacement), player)
            h.storage.onBreak(replacementBreak)
            replacementBreak.isCancelled shouldBe true
            h.fence.complete(Unit)
            first.join().pendingControls shouldBe emptyList()
            h.mutations shouldBe 1
            h.barriers shouldBe 1
        }
    }

    // Pinned MockBukkit InventoryMock.getHolder(boolean) throws UnimplementedOperationException;
    // BarrelInventoryMock inherits it. Keep native hopper insertion as a separate uncovered scenario.
    test("pending retirement cancels native InventoryMove inserts - MockBukkit getHolder(boolean) unsupported").config(enabled = false) {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            val late = InventoryMoveItemEvent(h.oldInventory, ItemStack(Material.STONE), h.receiverInventory, true)
            h.storage.onMove(late)
            late.isCancelled shouldBe true
            h.installations.install(h.city.id, h.building.id)
            val newInventory = (h.block(h.replacement).state as Barrel).inventory
            val insert = InventoryMoveItemEvent(h.receiverInventory, ItemStack(Material.STONE), newInventory, true)
            h.storage.onMove(insert)
            insert.isCancelled shouldBe true
        }
    }

    test("unknown retirement persistence survives restart and retries only the AIR fence without debiting inventories") {
        ControlRelocationFixture().use { h ->
            h.receiverInventory.setItem(0, ItemStack(Material.DIAMOND, 7))
            h.acceptIntent()
            val first = h.installations.install(h.city.id, h.building.id)
            h.fence.completeExceptionally(IOException("unknown chunk acknowledgement"))
            shouldThrow<CompletionException> { first.join() }
            h.city.pendingControls!!.size shouldBe 1
            h.block(h.old).type shouldBe Material.AIR
            val unrelated = controlTestOrder(h.hall.copy(level = 2))
            h.city = CivsRules.validate(h.city.copy(construction = unrelated))
            h.city = CivsRules.validate(Gson().fromJson(Gson().toJson(h.city), Settlement::class.java))
            h.storage.updateCity(h.city)
            h.fence = CompletableFuture.completedFuture(Unit)
            var debits = 0
            CivsControlRelocations(h.plugin, h.tasks, h.worldAdapter, h.storage, h.installations,
                { CompletableFuture.completedFuture(emptyList()) }, { listOf(h.city) }, { true },
                { _, _, _, _ -> debits++; error("An absent old barrel must never be debited again") }, h::mutate, { 10_000L }).use { owner ->
                owner.pulse()
                owner.drained().join()
            }
            debits shouldBe 0
            h.city.pendingControls shouldBe emptyList()
            h.city.construction shouldBe unrelated
            h.receiverInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 7)
            h.barriers shouldBe 2
        }
    }

    test("late source items permissions and occupied replacement cells defer retirement without dropping items") {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            h.oldInventory.setItem(0, ItemStack(Material.DIAMOND, 3))
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.building.id) }.reason shouldBe "storage-busy"
            h.oldInventory.getItem(0) shouldBe ItemStack(Material.DIAMOND, 3)
            h.block(h.old).type shouldBe Material.BARREL
            h.city.pendingControls!!.size shouldBe 1
            h.barriers shouldBe 0
        }
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            h.installations.controlOccupied = { _, point -> point == h.replacement }
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.building.id) }.reason shouldBe "worksite-blocked"
            h.block(h.replacement).type shouldBe Material.AIR
            h.block(h.old).type shouldBe Material.BARREL
            h.barriers shouldBe 0
        }
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            h.denied = h.old
            shouldThrow<CivsRejection> { h.installations.install(h.city.id, h.building.id) }.reason shouldBe "lands-denied"
            h.block(h.old).type shouldBe Material.BARREL
            h.barriers shouldBe 0
        }
    }

    test("the retiring building cannot queue or reserve its own upgrade while unrelated work remains legal") {
        ControlRelocationFixture().use { h ->
            h.acceptIntent()
            val city = h.city.copy(buildings = h.city.buildings.map { if (it.id == h.hall.id) it.copy(level = 2) else it })
            shouldThrow<CivsRejection> { CivsRules.enqueue(city, city.owner, h.original.id) }.reason shouldBe "city-busy"
            val clear = city.copy(pendingControls = emptyList())
            val queued = CivsRules.enqueue(clear, clear.owner, h.original.id)
            queued.constructionQueue shouldBe listOf(h.original.id)
            val upgrade = h.building.copy(level = 2, schematicId = "fixture/smeltery2")
            val order = controlTestOrder(upgrade)
            val pending = city.copy(constructionQueue = queued.constructionQueue)
            shouldThrow<CivsRejection> { CivsRules.reserveBuild(pending, pending.owner, upgrade, order) }.reason shouldBe "city-busy"
            CivsRules.reserveBuild(queued, queued.owner, upgrade, order).construction shouldBe order
        }
    }
})

private fun controlTestOrder(building: CivBuilding): CivConstruction = CivConstruction(UUID.randomUUID(), building.id, building.level,
    1, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building.copy(phase = BuildingPhase.CONSTRUCTING),
    batches = CivConstructionBatches(1, 1), materials = emptyMap(), payment = CivConstructionPayment())

private class ControlRelocationFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin("ControlRelocationTest")
    val world = paper.addSimpleWorld("control-relocation").also { it.loadChunk(0, 0); it.loadChunk(1, 0) }
    val old = CivPosition(11, 64, 10)
    val replacement = CivPosition(4, 64, 4)
    val receiver = CivPosition(6, 64, 6)
    val original = CivBuilding(UUID(0, 2), BuildingType.SMELTERY, 1, CivPosition(10, 63, 10), 0, "fixture/smeltery",
        CivBox(CivPosition(0, 63, 0), CivPosition(20, 70, 10)), CivPosition(10, 64, 11), BuildingPhase.ACTIVE, old, "STONE")
    val hall = CivBuilding(UUID(0, 3), BuildingType.TOWN_HALL, 1, CivPosition(24, 63, 12), 0, "fixture/hall",
        CivBox(CivPosition(23, 63, 11), CivPosition(26, 68, 14)), CivPosition(24, 64, 15), BuildingPhase.ACTIVE, CivPosition(24, 64, 12))
    var city = CivsRules.validate(Settlement(UUID(0, 4), UUID(0, 5), "Controls", world.uid, CivPosition(10, 64, 10), 96,
        "fixture", Stock(), listOf(hall, original), emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
    val building get() = requireNotNull(city.building(original.id))
    var denied: CivPosition? = null
    var fence = CompletableFuture<Unit>()
    var barriers = 0
    var mutations = 0
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val worldAdapter = mockk<CivsWorld>(relaxed = true).also { adapter -> every { adapter.canModify(any(), any(), any()) } answers {
        val block = secondArg<org.bukkit.block.Block>()
        CivPosition(block.x, block.y, block.z) != denied
    } }
    val storage = CivsStorage(plugin, tileEntities = { chunk -> listOf(old, receiver, hall.control!!, replacement)
        .filter { it.x shr 4 == chunk.x && it.z shr 4 == chunk.z }.map(::block).filter { it.type.name in CIVS_STORAGE_TYPES }
        .map { it.state }.toTypedArray() })
    val installations = CivsControlInstallations(plugin, worldAdapter, storage, mockk(relaxed = true), { mapOf(city.id to city) },
        emptySet(), emptySet(), { true }, ::mutate)
    val oldInventory get() = (block(old).state as Barrel).inventory
    val receiverInventory get() = (block(receiver).state as Chest).blockInventory
    init {
        block(old).type = Material.BARREL
        block(receiver).type = Material.CHEST
        block(requireNotNull(hall.control)).type = Material.BARREL
        storage.foodCanModify = { _, point -> point != denied }
        storage.relocationCurrentCity = { city.takeIf { candidate -> candidate.id == it } }
        storage.persistControlRetirementNative = { _, receipt ->
            receipt.retire!!.position shouldBe old
            block(old).type shouldBe Material.AIR
            block(replacement).type shouldBe Material.BARREL
            barriers++
            fence
        }
        storage.replaceCities(listOf(city))
    }
    fun block(p: CivPosition) = world.getBlockAt(p.x, p.y, p.z)
    fun mutate(id: UUID, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
        id shouldBe city.id
        city = transform(city)
        storage.updateCity(city)
        mutations++
        return CompletableFuture.completedFuture(city)
    }
    fun acceptIntent() {
        val receipt = CivControlPlacement(original.id, replacement, "minecraft:air", retire = CivPlanBlock(old,
            block(old).blockData.asString, "minecraft:air"))
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == original.id) it.copy(control = replacement) else it },
            pendingControls = listOf(receipt), revision = city.revision + 1))
        storage.updateCity(city)
    }
    fun prepare(): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val move = requireNotNull(storage.prepareControlRelocation(city, building, old, replacement))
        return CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1), emptyList(),
            containerRelocation = move).also { storage.seal(it) }
    }
    override fun close() { tasks.close(); storage.close(); paper.close() }
}
