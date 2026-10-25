package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Chest
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsWarehouseAlignmentTest : FunSpec({
    test("empty exact legacy halves join in one scan step and repeat without changes") {
        warehouseAlignment { fixture ->
            val result = fixture.repair()
            fixture.blocks.map { it.blockData.asString } shouldBe fixture.targets
            result shouldBe mapOf("changed" to 2, "unchanged" to 0, "conflicts" to 0, "occupied" to 0, "denied" to 0)
            fixture.blocks.map { (it.state as Chest).blockInventory.size } shouldBe listOf(27, 27)
            fixture.repair().getValue("changed") shouldBe 0
        }
    }

    test("an exact remaining shelf and exact previous chest can share the same guarded repair") {
        warehouseAlignment { fixture ->
            fixture.blocks[0].type = Material.BOOKSHELF
            fixture.repair().getValue("changed") shouldBe 2
            fixture.blocks.map { it.blockData.asString } shouldBe fixture.targets
        }
    }

    for (guard in listOf("filled-first", "filled-second", "filled-complete-half", "pdc", "viewer", "drift", "foreign-link", "blocked-lid", "denied"))
    test("warehouse alignment keeps both halves unchanged for $guard") {
        warehouseAlignment { fixture ->
            val chest = fixture.blocks[if (guard == "filled-second") 1 else 0]
            if (guard == "filled-complete-half") chest.blockData = Bukkit.createBlockData(fixture.targets[0])
            when {
                guard.startsWith("filled") -> (chest.state as Chest).blockInventory.setItem(3, ItemStack(Material.DIAMOND, 7))
                guard == "pdc" -> (chest.state as Chest).apply {
                    persistentDataContainer.set(NamespacedKey("arccivs", "retained"), PersistentDataType.INTEGER, 42)
                    update(true, false)
                }
                guard == "viewer" -> fixture.paper.addPlayer("WarehouseViewer").openInventory((chest.state as Chest).blockInventory)
                guard == "drift" -> chest.blockData = Bukkit.createBlockData("minecraft:chest[facing=north,type=single,waterlogged=false]")
                guard == "foreign-link" -> fixture.world.getBlockAt(-1, 64, 0).blockData =
                    Bukkit.createBlockData("minecraft:chest[facing=south,type=right,waterlogged=false]")
                guard == "blocked-lid" -> fixture.world.getBlockAt(1, 65, 0).type = Material.STONE
                guard == "denied" -> fixture.paper.server.pluginManager.disablePlugin(fixture.paper.createSimplePlugin("Lands"))
            }
            val before = fixture.blocks.map { it.blockData.asString }
            val result = fixture.repair()
            fixture.blocks.map { it.blockData.asString } shouldBe before
            result.getValue("changed") shouldBe 0
            result.getValue(when (guard) { "drift" -> "conflicts"; "foreign-link", "blocked-lid", "denied" -> "denied"; else -> "occupied" }) shouldBe 2
            if (guard.startsWith("filled")) (chest.state as Chest).blockInventory.getItem(3) shouldBe ItemStack(Material.DIAMOND, 7)
            if (guard == "pdc") (chest.state as Chest).persistentDataContainer
                .get(NamespacedKey("arccivs", "retained"), PersistentDataType.INTEGER) shouldBe 42
        }
    }

    test("missing reciprocal half and conflicting target alternatives fail before native writes") {
        warehouseAlignment { fixture ->
            for (cells in listOf(fixture.cells.filter { it.position.x == 0 }, fixture.cells + fixture.cells.first().copy(after = "minecraft:air"))) {
                val before = fixture.blocks.map { it.blockData.asString }
                val pending = fixture.subject.repairStorageRacks(fixture.city, fixture.building, cells)
                fixture.scheduler.tick()
                pending.isCompletedExceptionally shouldBe true
                fixture.blocks.map { it.blockData.asString } shouldBe before
            }
        }
    }
})

private fun warehouseAlignment(action: (WarehouseAlignmentFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open().use { paper -> WarehouseAlignmentFixture(paper).use(action) }
}

private class WarehouseAlignmentFixture(val paper: MockBukkitTestRuntime) : AutoCloseable {
    val world = paper.addSimpleWorld("warehouse-alignment")
    val scheduler = TestTaskScheduler()
    private val tasks = LifecycleTaskScope(scheduler, true)
    val building = CivBuilding(UUID.randomUUID(), BuildingType.WAREHOUSE, 1, CivPosition(0, 64, 0), 0, "test",
        CivBox(CivPosition(0, 64, 0), CivPosition(1, 64, 0)), CivPosition(0, 64, 1), BuildingPhase.ACTIVE)
    val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Warehouse", world.uid, CivPosition(0, 64, 0),
        100, "test", Stock(), listOf(building), emptyList())
    val subject = CivsWorld(paper.createSimplePlugin("WarehouseAlignment"), tasks, { 0L }) { listOf(city) }
    val before = listOf("minecraft:chest[facing=west,type=single,waterlogged=false]",
        "minecraft:chest[facing=east,type=single,waterlogged=false]")
    val targets = listOf("minecraft:chest[facing=south,type=right,waterlogged=false]",
        "minecraft:chest[facing=south,type=left,waterlogged=false]")
    val blocks = (0..1).map { x -> world.getBlockAt(x, 64, 0).also {
        // setBlockData alone leaves MockBukkit's retained tile snapshot stale; initialize both together.
        it.type = Material.CHEST
        it.state.apply {
            blockData = Bukkit.createBlockData(before[x])
            update(true, false) shouldBe true
        }
        it.blockData.asString shouldBe before[x]
    } }
    val cells = (0..1).flatMap { x -> listOf("minecraft:bookshelf", before[x]).map { old ->
        CivPlanBlock(CivPosition(x, 64, 0), old, targets[x])
    } }
    init { for (x in -1..1) for (z in -1..1) world.loadChunk(x, z) }
    fun repair(): Map<String, Int> {
        val pending = subject.repairStorageRacks(city, building, cells)
        scheduler.tick()
        pending.isDone shouldBe true
        return pending.join()
    }
    override fun close() { subject.close(); tasks.close() }
}
