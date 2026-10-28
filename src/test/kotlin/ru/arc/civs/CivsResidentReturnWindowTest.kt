package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsResidentReturnWindowTest : FunSpec({
    for (auto in listOf(false, true)) test("resident return bounds native reads and reaches later room on retry Auto=$auto") {
        MockBukkitTestRuntime.open().use {
            ResidentReturnWindowFixture(auto).use { h ->
                val first = h.prepare()
                h.assertBounded(128)
                first.worker shouldBe h.worker
                first.plan.changes shouldBe emptyList()
                h.clearReads()
                val secondId = UUID.randomUUID()
                h.storage.reserve(h.city, secondId)
                val second = h.storage.prepareResidentReturn(h.city, h.worker.id, h.building.id)
                h.assertBounded(128)
                second.plan.changes.single().container shouldBe h.positions.last()
                second.plan.changes.single().after shouldBe CivPlainItem("OAK_LOG", 64)
                second.worker.items shouldBe h.worker.items!!.mapIndexed { slot, item -> if (slot == 6) item!!.copy(amount = 60) else item }
                second.worker.completedCycles shouldBe h.worker.completedCycles
                second.worker.nextDeliveryAt shouldBe h.worker.nextDeliveryAt
                val after = h.city.copy(workers = listOf(second.worker), revision = h.city.revision + 1)
                val tx = CivInventoryTransaction(secondId, h.city.id, h.city.revision, after, second.plan.changes).validated()
                h.storage.seal(tx, keepViewersOpen = true)
                h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                h.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                h.contents.getValue(h.positions.last()).filterNotNull().filter { it.type == Material.OAK_LOG }
                    .sumOf(ItemStack::getAmount) shouldBe 64
                h.storage.unlock(secondId)
            }
        }
    }

    test("personal items need no native snapshots and retired workers lose their return cursor") {
        MockBukkitTestRuntime.open().use {
            ResidentReturnWindowFixture(false).use { h ->
                h.prepare()
                h.assertBounded(128)
                val personal = h.worker.copy(items = h.worker.items!!.mapIndexed { slot, item -> if (slot == 6) null else item })
                val personalCity = h.city.copy(workers = listOf(personal))
                val id = UUID.randomUUID()
                h.storage.reserve(personalCity, id)
                h.clearReads()
                val untouched = h.storage.prepareResidentReturn(personalCity, personal.id, h.building.id)
                untouched.worker shouldBe personal
                untouched.plan.changes shouldBe emptyList()
                h.assertBounded(0)
                h.storage.unlock(id)
                h.storage.updateCity(h.city.copy(workers = emptyList()))
                h.storage.updateCity(h.city)
                val retry = h.prepare()
                h.assertBounded(128)
                retry.plan.changes shouldBe emptyList() // Re-admitted identity starts at the first window.
                retry.worker shouldBe h.worker
            }
        }
    }
})

/** The index sees metadata once; counters start at the native return boundary after reservation. */
private class ResidentReturnWindowFixture(auto: Boolean) : AutoCloseable {
    val positions = (0..15).flatMap { x -> (0..15).map { z -> CivPosition(x, 64, z) } }.take(129)
    val building = CivBuilding(UUID.randomUUID(), BuildingType.FORESTER, 1, positions.first(), 0, "fixture/forester",
        CivBox(positions.first(), CivPosition(15, 68, 15)), CivPosition(0, 64, -1), BuildingPhase.ACTIVE,
        control = positions.first())
    val worker = CivWorker(UUID.randomUUID(), "Returner", building.id, completedCycles = 7, nextDeliveryAt = 900,
        items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
            4 -> CivResidentItem("IRON_AXE", 1)
            6 -> CivResidentItem("OAK_LOG", 64)
            7 -> CivResidentItem("OAK_SAPLING", 4)
            8 -> CivResidentItem("BREAD", 16)
            9 -> CivResidentItem.of(ItemStack(Material.OAK_LOG).also { stack ->
                stack.editMeta { it.displayName(Component.text("Personal log")) }
            })
            else -> null
        } })
    private val hall = building.copy(id = UUID.randomUUID(), type = BuildingType.TOWN_HALL,
        anchor = CivPosition(24, 64, 0), bounds = CivBox(CivPosition(24, 64, 0), CivPosition(26, 68, 2)),
        entrance = CivPosition(24, 64, -1), control = null, schematicId = "fixture/hall")
    val city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Return window", UUID.randomUUID(),
        CivPosition(0, 64, 0), 32, "nordic", Stock(), listOf(hall, building), listOf(worker),
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy().takeIf { auto }))
    val contents = positions.associateWith { p -> Array<ItemStack?>(27) { slot ->
        if (p == positions.last() && slot == 0) ItemStack(Material.OAK_LOG, 60) else ItemStack(Material.STONE, 64)
    } }
    private val reads = mutableMapOf<CivPosition, Int>()
    private val snapshots = mutableMapOf<CivPosition, Int>()
    private var scans = 0
    val storage: CivsStorage

    init {
        val nativeWorld = mockk<World>()
        val states = positions.associateWith { p ->
            val inventory = mockk<Inventory>()
            every { inventory.size } returns 27
            every { inventory.viewers } returns emptyList()
            every { inventory.storageContents } answers {
                snapshots.merge(p, 1, Int::plus)
                contents.getValue(p).copyOf()
            }
            every { inventory.getItem(any()) } answers { contents.getValue(p)[firstArg<Int>()] }
            every { inventory.setItem(any(), any()) } answers { contents.getValue(p)[firstArg<Int>()] = secondArg() }
            mockk<Barrel> {
                every { type } returns Material.BARREL
                every { x } returns p.x; every { y } returns p.y; every { z } returns p.z
                every { this@mockk.inventory } returns inventory
            }
        }
        val blocks = states.mapValues { (_, state) -> mockk<Block> {
            every { type } returns Material.BARREL
            every { this@mockk.state } returns state
        } }
        val chunk = mockk<Chunk> {
            every { world } returns nativeWorld
            every { x } returns 0; every { z } returns 0
        }
        every { nativeWorld.uid } returns city.worldId
        every { nativeWorld.name } returns "return-window"
        every { nativeWorld.minHeight } returns -64
        every { nativeWorld.maxHeight } returns 320
        every { nativeWorld.loadedChunks } returns arrayOf(chunk)
        every { nativeWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { nativeWorld.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val p = CivPosition(firstArg(), secondArg(), thirdArg())
            reads.merge(p, 1, Int::plus)
            blocks.getValue(p)
        }
        val server = mockk<Server> {
            every { pluginManager } returns mockk(relaxed = true)
            every { onlinePlayers } returns emptyList()
            every { getWorld(city.worldId) } returns nativeWorld
        }
        storage = CivsStorage(mockk<Plugin> { every { this@mockk.server } returns server }) {
            scans++
            states.values.toTypedArray<BlockState>()
        }
    }

    fun prepare(): CivResidentTransferPlan {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        clearReads()
        return try { storage.prepareResidentReturn(city, worker.id, building.id) } finally { storage.unlock(id) }
    }
    fun assertBounded(exact: Int) {
        reads.size shouldBe exact
        reads.values.all { it == 1 } shouldBe true
        snapshots shouldBe reads
        scans shouldBe 1
    }
    fun clearReads() { reads.clear(); snapshots.clear() }
    override fun close() = storage.close()
}
