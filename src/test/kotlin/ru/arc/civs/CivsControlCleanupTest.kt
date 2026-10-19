package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsControlCleanupTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var city: Settlement
    lateinit var cleanup: CivsControlCleanup
    var denyX: Int? = null

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.addSimpleWorld("cleanup")
        world.loadChunk(0, 0)
        plugin = paper.createSimplePlugin("CivsControlCleanupTest")
        val control = CivPosition(0, 64, 0)
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
            "test", CivBox(CivPosition(0, 64, 0), CivPosition(5, 66, 5)), CivPosition(3, 64, 6),
            BuildingPhase.SITE, control)
        city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Cleanup", world.uid, control, 16,
            "nordic", hall, 1_000)
        denyX = null
        cleanup = CivsControlCleanup(plugin) { owner, block ->
            owner shouldBe city.owner
            block.x != denyX
        }
        world.getBlockAt(0, 64, 0).type = Material.BARREL
    }
    afterEach { paper.close() }

    test("removal drops exact native contents once without granting a barrel or touching nearby storage") {
        val block = world.getBlockAt(0, 64, 0)
        val inventory = (block.state as Barrel).inventory
        val named = ItemStack(Material.DIAMOND, 3).apply {
            itemMeta = itemMeta.apply { displayName(Component.text("Keep my custom item")) }
        }
        inventory.setItem(0, named)
        inventory.setItem(1, ItemStack(Material.OAK_LOG, 17))
        world.getBlockAt(1, 64, 0).type = Material.CHEST
        world.getBlockAt(2, 64, 0).type = Material.BARREL

        val controls = cleanup.prepare(city, city.buildings)
        block.type shouldBe Material.BARREL
        inventory.getItem(0) shouldBe named
        cleanup.apply(city, controls) shouldBe 1
        block.type shouldBe Material.AIR
        world.getBlockAt(1, 64, 0).type shouldBe Material.CHEST
        world.getBlockAt(2, 64, 0).type shouldBe Material.BARREL
        val dropped = world.entities.filterIsInstance<Item>().map(Item::getItemStack)
        dropped.size shouldBe 2
        dropped.single { it.type == Material.DIAMOND } shouldBe named
        dropped.single { it.type == Material.OAK_LOG }.amount shouldBe 17
        cleanup.apply(city, controls) shouldBe 0
        world.entities.filterIsInstance<Item>().size shouldBe 2
    }

    test("denied second control prevents every write and apply rechecks changed permission") {
        val other = city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
            control = CivPosition(2, 64, 0))
        city = city.copy(buildings = city.buildings + other)
        world.getBlockAt(2, 64, 0).type = Material.BARREL
        val controls = cleanup.prepare(city, city.buildings)
        denyX = 2
        shouldThrow<CivsRejection> { cleanup.apply(city, controls) }.reason shouldBe "lands-denied"
        world.getBlockAt(0, 64, 0).type shouldBe Material.BARREL
        world.getBlockAt(2, 64, 0).type shouldBe Material.BARREL
        world.entities.filterIsInstance<Item>().size shouldBe 0
    }

    test("player replacement and unregistered coordinates are never removed") {
        val controls = cleanup.prepare(city, city.buildings)
        world.getBlockAt(0, 64, 0).type = Material.BEDROCK
        cleanup.apply(city, controls) shouldBe 0
        world.getBlockAt(0, 64, 0).type shouldBe Material.BEDROCK
        shouldThrow<IllegalArgumentException> { cleanup.apply(city, listOf(CivPosition(1, 64, 0))) }
        shouldThrow<IllegalArgumentException> { cleanup.prepare(city, listOf(city.buildings.single().copy(control = CivPosition(1, 64, 0)))) }
    }

    test("an unloaded control is deferred before any chunk or block is accessed") {
        val remote = city.buildings.single().copy(control = CivPosition(512, 64, 0))
        city = city.copy(buildings = listOf(remote))
        world.isChunkLoaded(32, 0) shouldBe false
        shouldThrow<CivsRejection> { cleanup.prepare(city, city.buildings) }.reason shouldBe "chunk-unloaded"
        world.isChunkLoaded(32, 0) shouldBe false
    }
})
