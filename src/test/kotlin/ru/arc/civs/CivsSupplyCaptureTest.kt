package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsSupplyCaptureTest : FunSpec({
    test("captured producer output room reserves two slots in each native holder") {
        supplyCaptureScenario { h ->
            h.city = h.city.copy(buildings = h.city.buildings.map {
                if (it.id == h.warehouse.id) it.copy(type = BuildingType.QUARRY) else it
            })
            val first = h.nodes.getValue(requireNotNull(h.warehouse.control))
            val second = h.nodes.getValue(CivPosition(2, 64, 0))
            repeat(26) { first.contents[it] = ItemStack(Material.STONE, 64) }
            repeat(24) { second.contents[it] = ItemStack(Material.STONE, 64) }
            val capacity = h.capture().stores.getValue(h.warehouse.id).capacity
            capacity.emptySlots shouldBe 4
            capacity.outputEmptySlots shouldBe 1
        }
    }

    test("Auto captures actual slots separately for the producer and warehouse without counting an outside chest half") {
        supplyCaptureScenario { h ->
            h.city = h.city.copy(inventory = null, autonomy = CivAutonomy())
            h.storage.foodCanModify = { _, _ -> true }
            val first = h.capture()
            val producer = first.stores.getValue(h.producer.id)
            producer.containers shouldBe 1
            producer.slots shouldBe 27
            producer.capacity.emptySlots shouldBe 27
            first.stores.getValue(h.warehouse.id).slots shouldBe 54
            h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(storage = CivsAutonomousStorage.stores(first)))
            h.storage.rememberAutonomousCapacity(h.city, first)
            h.storage.autonomousCapacityCurrent(h.city, h.producer.id) shouldBe true
            h.nodes.getValue(CivPosition(3, 64, 0)).reads shouldBe 0
            val local = h.nodes.getValue(h.producer.control!!)
            local.contents.indices.forEach { local.contents[it] = ItemStack(Material.COBBLESTONE, 64) }
            h.storage.supplyPickup(h.pickup(h.producer.control!!))
            h.storage.autonomousCapacityCurrent(h.city, h.producer.id) shouldBe false
            // A save finishing after this inventory event must not republish its old positive capacity as current.
            h.storage.rememberAutonomousCapacity(h.city, first)
            h.storage.autonomousCapacityCurrent(h.city, h.producer.id) shouldBe false
            val full = h.capture()
            full.stores.getValue(h.producer.id).capacity.emptySlots shouldBe 0
            full.stores.getValue(h.warehouse.id).capacity.emptySlots shouldBe 54
            h.city = h.city.copy(autonomy = h.city.autonomy!!.copy(storage = CivsAutonomousStorage.stores(full)))
            h.storage.rememberAutonomousCapacity(h.city, full)
            h.storage.autonomousCapacityCurrent(h.city, h.producer.id) shouldBe true
            h.storage.supplyCaptureCurrent(h.city, first, setOf(h.producer.id)) shouldBe false
            h.storage.supplyCaptureCurrent(h.city, full) shouldBe true
            h.storage.foodCanModify = { _, position -> position != h.producer.control }
            val denied = h.capture().stores.getValue(h.producer.id)
            denied.slots shouldBe 0
            denied.unavailable shouldBe 1
            denied.capacity.emptySlots shouldBe 0
        }
    }

    test("one step reads one chest half and capacity shares empty slots while excluding custom items") {
        supplyCaptureScenario { h ->
            val barrel = h.nodes.getValue(h.warehouse.control!!)
            barrel.contents[0] = ItemStack(Material.OAK_LOG, 60)
            barrel.contents[1] = ItemStack(Material.EGG, 14)
            barrel.contents[2] = ItemStack(Material.COAL, 63).also { item ->
                item.itemMeta = item.itemMeta.also { it.displayName(Component.text("Named coal")) }
            }
            val chest = h.nodes.getValue(CivPosition(2, 64, 0))
            chest.contents[0] = ItemStack(Material.OAK_LOG, 64)
            h.nodes.getValue(CivPosition(3, 64, 0)).contents[0] = ItemStack(Material.COAL, 64)
            val cursor = h.storage.startSupplyCapture(h.city)
            h.reads shouldBe 0
            cursor.result shouldBe null
            var completed = false
            for (step in 1..8) {
                val before = h.reads
                completed = cursor.step()
                h.reads shouldBe before + 1
                if (completed) break
                cursor.result shouldBe null
            }
            completed shouldBe true
            h.reads shouldBe 4 // Hall control, warehouse barrel + one chest half, producer control.
            h.nodes.getValue(CivPosition(3, 64, 0)).reads shouldBe 0
            verify(exactly = 0) { (chest.state as Chest).inventory }
            val result = requireNotNull(cursor.result)
            val captured = result.stores.getValue(h.warehouse.id)
            captured.stock shouldBe mapOf("OAK_LOG" to 124L, "EGG" to 14L)
            captured.capacity shouldBe CivSupplyCapacity(50, mapOf("OAK_LOG" to 4L, "EGG" to 2L))
            h.storage.supplyCaptureCurrent(h.city, result) shouldBe true
            barrel.contents[0]!!.amount = 1
            captured.stock["OAK_LOG"] shouldBe 124L
        }
    }

    test("an event between native reads rejects only the changed building in the completed capture") {
        supplyCaptureScenario { h ->
            val cursor = h.storage.startSupplyCapture(h.city)
            cursor.step() shouldBe false
            h.storage.supplyPickup(h.pickup(h.warehouse.control!!))
            while (!cursor.step()) Unit
            val result = requireNotNull(cursor.result)
            h.storage.supplyCaptureCurrent(h.city, result) shouldBe false
            h.storage.supplyCaptureCurrent(h.city, result, setOf(h.warehouse.id)) shouldBe false
            h.storage.supplyCaptureCurrent(h.city, result, setOf(h.producer.id)) shouldBe true
            h.storage.supplyCaptureCurrent(h.city, h.capture()) shouldBe true
        }
    }

    test("periodic bounded recapture detects another plugin's native write without an event") {
        supplyCaptureScenario { h ->
            val first = h.capture()
            val revision = h.storage.supplyRevision(h.city.id)
            h.nodes.getValue(h.warehouse.control!!).contents[0] = ItemStack(Material.BREAD, 9)
            // An unannounced native write is discovered by the next bounded periodic cursor.
            h.storage.supplyCaptureCurrent(h.city, first) shouldBe true
            val next = h.capture()
            (h.storage.supplyRevision(h.city.id) > revision) shouldBe true
            next.stores.getValue(h.warehouse.id).stock shouldBe mapOf("BREAD" to 9L)
            h.storage.supplyCaptureCurrent(h.city, first, setOf(h.warehouse.id)) shouldBe false
            h.storage.supplyCaptureCurrent(h.city, first, setOf(h.producer.id)) shouldBe true
            h.storage.supplyCaptureCurrent(h.city, next) shouldBe true
        }
    }

    test("virtual room is shared by all materials and unrelated city or worker metadata preserves keys") {
        supplyCaptureScenario { h ->
            h.city = h.city.copy(inventory = requireNotNull(h.city.inventory).copy(mode = CivStorageMode.VIRTUAL,
                buildingMaterials = mapOf(h.warehouse.id.toString() to mapOf("OAK_LOG" to 100L, "BREAD" to 200L))))
            val result = h.capture()
            h.reads shouldBe 0
            result.stores.getValue(h.warehouse.id).capacity shouldBe CivSupplyCapacity(0, emptyMap(), 1_428L)
            val revision = h.storage.supplyRevision(h.city.id)
            h.city = h.city.copy(name = "Renamed", revision = h.city.revision + 1,
                workers = h.city.workers.map { it.copy(hunger = 17) })
            h.storage.updateCity(h.city)
            h.storage.supplyRevision(h.city.id) shouldBe revision
            h.storage.supplyCaptureCurrent(h.city, result) shouldBe true
            h.city = h.city.copy(buildings = h.city.buildings.map {
                if (it.id == h.warehouse.id) it.copy(level = 2) else it
            })
            h.storage.supplyCaptureCurrent(h.city, result, setOf(h.warehouse.id)) shouldBe false
            h.storage.supplyCaptureCurrent(h.city, result, setOf(h.producer.id)) shouldBe true
            h.reads shouldBe 0
        }
    }

    test("inventory monitor hooks dirty relevant buildings without invalidating another building or unrelated holder") {
        supplyCaptureScenario { h ->
            val inventory = h.nodes.getValue(h.warehouse.control!!).inventory
            val view = mockk<InventoryView> { every { topInventory } returns inventory }
            val events = listOf<() -> Unit>(
                { h.storage.supplyClick(mockk<InventoryClickEvent> { every { this@mockk.view } returns view }) },
                { h.storage.supplyDrag(mockk<InventoryDragEvent> { every { this@mockk.view } returns view }) },
                { h.storage.supplyMove(InventoryMoveItemEvent(inventory, ItemStack(Material.STONE),
                    h.nodes.getValue(CivPosition(2, 64, 0)).inventory, true)) },
                { h.storage.supplyPickup(h.pickup(h.warehouse.control!!)) },
            )
            events.forEach { event ->
                val before = h.capture()
                val revision = h.storage.supplyRevision(h.city.id)
                event()
                (h.storage.supplyRevision(h.city.id) > revision) shouldBe true
                h.storage.supplyCaptureCurrent(h.city, before, setOf(h.warehouse.id)) shouldBe false
                h.storage.supplyCaptureCurrent(h.city, before, setOf(h.producer.id)) shouldBe true
            }
            val before = h.capture()
            val revision = h.storage.supplyRevision(h.city.id)
            h.storage.supplyPickup(h.pickup(CivPosition(3, 64, 0)))
            h.storage.supplyRevision(h.city.id) shouldBe revision
            h.storage.supplyCaptureCurrent(h.city, before) shouldBe true
        }
    }
})

class CivsUpgradeStorageCapacityTest : FunSpec({
    test("an actual upgrade invalidates prior capacity and excludes old chest room while retaining its stock") {
        supplyCaptureScenario { h ->
            val control = h.nodes.getValue(requireNotNull(h.warehouse.control))
            repeat(STORAGE_SLOTS) { control.contents[it] = ItemStack(Material.STONE, 64) }
            val chest = h.nodes.getValue(CivPosition(2, 64, 0))
            chest.contents[0] = ItemStack(Material.OAK_LOG, 60)
            val previous = h.capture()
            previous.stores.getValue(h.warehouse.id).capacity shouldBe CivSupplyCapacity(26, mapOf("OAK_LOG" to 4L))
            val target = h.warehouse.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)
            h.city = h.city.copy(construction = CivConstruction(UUID.randomUUID(), h.warehouse.id, 2, 1, 0,
                ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = target))
            h.storage.supplyCaptureCurrent(h.city, previous, setOf(h.warehouse.id)) shouldBe false
            h.storage.supplyCaptureCurrent(h.city, previous, setOf(h.producer.id)) shouldBe true
            val current = h.capture().stores.getValue(h.warehouse.id)
            current.stock shouldBe mapOf("STONE" to (STORAGE_SLOTS * 64L), "OAK_LOG" to 60L)
            current.capacity shouldBe CivSupplyCapacity(0, emptyMap())
            current.slots shouldBe 54
            val during = h.capture()
            h.city = h.city.copy(construction = null)
            h.storage.supplyCaptureCurrent(h.city, during, setOf(h.warehouse.id)) shouldBe false
            h.capture().stores.getValue(h.warehouse.id).capacity shouldBe CivSupplyCapacity(26, mapOf("OAK_LOG" to 4L))
        }
    }
})

private fun supplyCaptureScenario(action: (SupplyCaptureFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> SupplyCaptureFixture(paper).use(action) }
}

/** Count the supported native boundary directly; Core supplies actual Bukkit threading and ItemStacks. */
private class SupplyCaptureFixture(paper: MockBukkitTestRuntime) : AutoCloseable {
    private val worldId = paper.addSimpleWorld("supply-capture").uid
    private val nativeWorld = mockk<World>()
    val nodes = linkedMapOf<CivPosition, SupplyCaptureNode>()
    private val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(-2, 64, 0), 0,
        "hall", CivBox(CivPosition(-4, 64, 0), CivPosition(0, 66, 2)), CivPosition(0, 64, 2),
        BuildingPhase.SITE, CivPosition(0, 64, 0))
    val warehouse = hall.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE,
        anchor = CivPosition(1, 64, 0), entrance = CivPosition(1, 64, 2), control = CivPosition(1, 64, 0),
        bounds = CivBox(CivPosition(1, 64, 0), CivPosition(2, 66, 2)))
    val producer = warehouse.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY,
        anchor = CivPosition(10, 64, 0), entrance = CivPosition(10, 64, 2), control = CivPosition(10, 64, 0),
        bounds = CivBox(CivPosition(10, 64, 0), CivPosition(12, 66, 2)))
    var city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Supply", worldId,
        CivPosition(0, 64, 0), 32, "nordic", hall, 1_000).copy(buildings = listOf(hall, warehouse, producer),
        inventory = CivInventory(CivStorageMode.PHYSICAL), workers = listOf(CivWorker(UUID.randomUUID(), "Worker", producer.id)))
    val storage: CivsStorage
    val reads: Int get() = nodes.values.sumOf { it.reads }

    init {
        listOf(0 to Material.BARREL, 1 to Material.BARREL, 2 to Material.CHEST, 3 to Material.CHEST, 10 to Material.BARREL)
            .forEach { (x, type) ->
                val position = CivPosition(x, 64, 0)
                nodes[position] = SupplyCaptureNode(nativeWorld, position, type)
            }
        val chunk = mockk<Chunk> {
            every { world } returns nativeWorld
            every { x } returns 0
            every { z } returns 0
        }
        every { nativeWorld.uid } returns worldId
        every { nativeWorld.name } returns "supply-native"
        every { nativeWorld.minHeight } returns -64
        every { nativeWorld.maxHeight } returns 320
        every { nativeWorld.loadedChunks } returns arrayOf(chunk)
        every { nativeWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { nativeWorld.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            nodes.getValue(CivPosition(firstArg(), secondArg(), thirdArg())).block
        }
        val server = mockk<Server> {
            every { pluginManager } returns mockk(relaxed = true)
            every { getWorld(worldId) } returns nativeWorld
        }
        val plugin = mockk<Plugin> { every { this@mockk.server } returns server }
        storage = CivsStorage(plugin) { nodes.values.map { it.state }.toTypedArray() }
    }

    fun capture(): CivSupplyCaptureResult {
        val cursor = storage.startSupplyCapture(city)
        for (step in 1..16) if (cursor.step()) return requireNotNull(cursor.result)
        error("Supply capture did not finish within its fixture's container bound")
    }
    fun pickup(position: CivPosition): InventoryPickupItemEvent = mockk {
        every { inventory } returns nodes.getValue(position).inventory
    }
    override fun close() = storage.close()
}

private class SupplyCaptureNode(world: World, position: CivPosition, type: Material) {
    val contents = arrayOfNulls<ItemStack>(27)
    var reads = 0
    val inventory = mockk<Inventory>()
    val state: BlockState = if (type == Material.CHEST) mockk<Chest> {
        every { blockInventory } returns this@SupplyCaptureNode.inventory
    } else mockk<Barrel> { every { this@mockk.inventory } returns this@SupplyCaptureNode.inventory }
    val block = mockk<Block> {
        every { this@mockk.type } returns type
        every { this@mockk.state } returns this@SupplyCaptureNode.state
    }
    init {
        every { state.type } returns type
        every { state.world } returns world
        every { state.x } returns position.x
        every { state.y } returns position.y
        every { state.z } returns position.z
        every { inventory.size } returns 27
        every { inventory.storageContents } answers { reads++; contents.copyOf() }
        every { inventory.holder } returns state as org.bukkit.inventory.InventoryHolder
    }
}
