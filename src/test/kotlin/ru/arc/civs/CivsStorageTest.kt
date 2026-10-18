package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.ExplosionResult
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.BlockFace
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsStorageTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var storage: CivsStorage
    lateinit var city: Settlement
    lateinit var barrel: Inventory
    lateinit var chest: Inventory
    lateinit var indexedBlocks: MutableList<Block>
    var scans = 0
    val control = CivPosition(0, 64, 0)
    val registered = CivPosition(1, 64, 0)

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.addSimpleWorld("storage")
        world.loadChunk(0, 0)
        plugin = paper.createSimplePlugin("CivsStorageTest")
        indexedBlocks = mutableListOf()
        scans = 0
        // Pinned MockBukkit does not implement Chunk.getTileEntities; supply only that native read.
        storage = CivsStorage(plugin) { chunk ->
            scans++
            indexedBlocks.filter { it.world.uid == chunk.world.uid && (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z }
                .map(Block::getState).toTypedArray()
        }
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        world.getBlockAt(1, 64, 0).type = Material.CHEST
        barrel = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
        chest = (world.getBlockAt(1, 64, 0).state as Chest).blockInventory
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
            "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3),
            BuildingPhase.SITE, control = control)
        city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Storage", world.uid,
            CivPosition(0, 64, 0), 16, "nordic", hall, 1_000).let {
            it.copy(inventory = requireNotNull(it.inventory).copy(materials = mapOf("OAK_LOG" to 3L), containers = listOf(registered)))
        }
    }
    afterEach { storage.close(); paper.close() }

    test("construction supplies transfer only missing plain storage cargo and preserve custom items and offhand") {
        val player = paper.addPlayer("Supplier")
        city = city.copy(owner = player.uniqueId)
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 10))
        val custom = named(Material.OAK_LOG, 64)
        player.inventory.setItem(1, custom)
        player.inventory.setItem(2, ItemStack(Material.BREAD, 2))
        player.inventory.setItemInOffHand(ItemStack(Material.BREAD, 20))
        val id = UUID.randomUUID()
        storage.lock(city, id, player)
        val supplied = storage.prepareConstructionSupply(city, city.buildings.single().id,
            mapOf("OAK_LOG" to 10L, "BREAD" to 5L), player)
        supplied.result shouldBe CivsSupplyResult(6, 3, false)
        player.inventory.getItem(0)!!.amount shouldBe 10
        barrel.getItem(0)!!.amount shouldBe 4
        val receipt = transaction(id, city, supplied.plan).validated()
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0)!!.amount shouldBe 6
        player.inventory.getItem(1) shouldBe custom
        player.inventory.getItem(2) shouldBe null
        player.inventory.itemInOffHand.amount shouldBe 20
        barrel.getItem(0)!!.amount shouldBe 8
        storage.available(city) shouldBe mapOf("BREAD" to 2L, "OAK_LOG" to 10L)
        supplied.plan.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 3L)
        (paper.playerDataSaveCount(player) > 0) shouldBe true
    }

    for (grant in listOf(false, true)) test("construction supply grant=$grant keeps overflow out of both native and virtual storage") {
        val player = paper.addPlayer("PartialSupplier")
        // Paper's current view always has a top inventory; MockBukkit's initial view leaves it null.
        player.openInventory(paper.server.createInventory(player, 9))
        city = city.copy(owner = player.uniqueId)
        for (slot in 0 until 27) {
            barrel.setItem(slot, ItemStack(Material.STONE, 64))
            chest.setItem(slot, ItemStack(Material.STONE, 64))
        }
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 62))
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 10))
        val id = UUID.randomUUID()
        storage.lock(city, id, if (grant) null else player)
        val supplied = storage.prepareConstructionSupply(city, city.buildings.single().id, mapOf("OAK_LOG" to 70L), if (grant) null else player)
        supplied.result shouldBe CivsSupplyResult(2, 6, true)
        storage.reconcile(transaction(id, city, supplied.plan)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)!!.amount shouldBe 64
        player.inventory.getItem(0)!!.amount shouldBe if (grant) 10 else 8
        supplied.plan.inventoryAfter shouldBe city.inventory
        val next = storage.prepareConstructionSupply(city, city.buildings.single().id, mapOf("OAK_LOG" to 70L), if (grant) null else player)
        next.result shouldBe CivsSupplyResult(0, 6, true)
        next.plan.changes shouldBe emptyList()
        next.plan.playerDebit shouldBe null
    }

    test("changed player metadata conflicts before every native write and before saving player data") {
        val player = paper.addPlayer("ChangedSupplier")
        city = city.copy(owner = player.uniqueId)
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 10))
        val id = UUID.randomUUID()
        storage.lock(city, id, player)
        val prepared = storage.prepareConstructionSupply(city, city.buildings.single().id, mapOf("OAK_LOG" to 4L), player)
        val custom = named(Material.OAK_LOG, 10)
        player.inventory.setItem(0, custom) // An external plugin bypassed the event lock while journal IO was pending.
        val beforeSaves = paper.playerDataSaveCount(player)
        val outcome = storage.reconcile(transaction(id, city, prepared.plan)) as CivInventoryApplyResult.Conflict
        outcome.values["reason"] shouldBe "player-slot-drift"
        player.inventory.getItem(0) shouldBe custom
        barrel.isEmpty shouldBe true
        chest.isEmpty shouldBe true
        paper.playerDataSaveCount(player) shouldBe beforeSaves
    }

    test("a failed player save never credits containers and recovery reuses the already applied debit") {
        val player = paper.addPlayer("DurableSupplier")
        city = city.copy(owner = player.uniqueId)
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 10))
        storage.close()
        var saves = 0
        val persistence = PaperPlayerDataPersistence { source ->
            saves++
            source.inventory.getItem(0)!!.amount shouldBe 6
            if (saves == 1) {
                barrel.isEmpty shouldBe true
                throw java.io.IOException("player save unavailable")
            }
        }
        storage = CivsStorage(plugin, persistence) { emptyArray() }
        val id = UUID.randomUUID()
        storage.lock(city, id, player)
        val receipt = transaction(id, city, storage.prepareConstructionSupply(city, city.buildings.single().id,
            mapOf("OAK_LOG" to 4L), player).plan)
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        player.inventory.getItem(0)!!.amount shouldBe 6
        barrel.isEmpty shouldBe true
        storage.close()
        storage = CivsStorage(plugin, persistence) { emptyArray() }
        storage.lock(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0)!!.amount shouldBe 6
        barrel.getItem(0)!!.amount shouldBe 4
    }

    test("offline supply recovery waits for its player and locks gameplay inventory mutations until release") {
        val player = paper.addPlayer("ReturningSupplier")
        city = city.copy(owner = player.uniqueId)
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 10))
        val id = UUID.randomUUID()
        storage.lock(city, id, player)
        val receipt = transaction(id, city, storage.prepareConstructionSupply(city, city.buildings.single().id,
            mapOf("OAK_LOG" to 4L), player).plan)
        val command = PlayerCommandPreprocessEvent(player, "/sellall")
        paper.callEvent(command).isCancelled shouldBe true
        paper.callEvent(PlayerItemConsumeEvent(player, ItemStack(Material.BREAD))).isCancelled shouldBe true
        shouldThrow<CivsRejection> { storage.lock(city, UUID.randomUUID(), player) }.reason shouldBe "storage-busy"
        player.disconnect()
        storage.waitingForPlayer(receipt) shouldBe true
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        barrel.isEmpty shouldBe true
        player.reconnect()
        storage.waitingForPlayer(receipt) shouldBe false
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0)!!.amount shouldBe 6
        storage.unlock(id)
        paper.callEvent(PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe false
        paper.callEvent(PlayerItemConsumeEvent(player, ItemStack(Material.BREAD))).isCancelled shouldBe false
    }

    test("player supply receipts reject duplicated slots foreign players growth and unequal material totals") {
        val player = paper.addPlayer("CheckedSupplier")
        city = city.copy(owner = player.uniqueId)
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 10))
        val id = UUID.randomUUID()
        storage.lock(city, id, player)
        val receipt = transaction(id, city, storage.prepareConstructionSupply(city, city.buildings.single().id,
            mapOf("OAK_LOG" to 4L), player).plan).validated()
        val debit = requireNotNull(receipt.playerDebit)
        val change = debit.changes.single()
        listOf(
            debit.copy(playerId = UUID.randomUUID()),
            debit.copy(changes = listOf(change, change)),
            debit.copy(changes = listOf(change.copy(slot = 36))),
            debit.copy(changes = listOf(change.copy(after = CivPlainItem("OAK_LOG", 11)))),
            debit.copy(changes = listOf(change.copy(after = CivPlainItem("OAK_LOG", 5)))),
        ).forEach { invalid -> shouldThrow<IllegalArgumentException> { receipt.copy(playerDebit = invalid).validated() } }
    }

    test("physical debit uses control before registered containers and never spends reserves or named cargo") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val custom = named(Material.OAK_LOG, 64)
        barrel.setItem(1, custom)
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 6L)
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 5L))
        plan.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 3L)
        plan.changes.size shouldBe 2
        barrel.getItem(0)?.amount shouldBe 4
        chest.getItem(0)?.amount shouldBe 2
        val transaction = transaction(id, city, plan)
        storage.apply(transaction) shouldBe CivInventoryApplyResult.Applied
        storage.apply(transaction) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe null
        chest.getItem(0)?.amount shouldBe 1
        barrel.getItem(1) shouldBe custom
    }

    test("insufficient debit reports the exact shortage without applying a partial removal") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        storage.lock(city, UUID.randomUUID())
        val failure = shouldThrow<CivsRejection> { storage.prepareDebit(city, mapOf("OAK_LOG" to 10L)) }
        failure.reason shouldBe "materials-missing"
        failure.values["material"] shouldBe "OAK_LOG"
        failure.values["count"] shouldBe "6"
        barrel.getItem(0)?.amount shouldBe 4
        requireNotNull(city.inventory).materials shouldBe mapOf("OAK_LOG" to 3L)
    }

    test("background debit counts unrelated containers and combines fresh candidates across bounded windows") {
        DebitWindowFixture(city).use { f ->
            val first = f.positions[10]
            val late = f.positions[200]
            f.contents.getValue(first)[0] = ItemStack(Material.OAK_LOG, 1)
            f.contents.getValue(late)[0] = ItemStack(Material.OAK_LOG, 1)
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            f.assertBounded(128)
            (late in f.reads) shouldBe false
            f.contents.getValue(first)[0]!!.amount shouldBe 1
            f.clearReads()
            val plan = requireNotNull(f.prepare(mapOf("OAK_LOG" to 2L)))
            f.assertBounded()
            f.reads[first] shouldBe 1 // Candidate is captured again, not retained as an ItemStack.
            f.reads[late] shouldBe 1
            plan.changes.map { it.container }.toSet() shouldBe setOf(first, late)
            plan.inventoryAfter.materials shouldBe f.city.inventory!!.materials // PHYSICAL never spends hidden reserves.
            f.contents.getValue(first)[0]!!.amount shouldBe 1
            f.contents.getValue(late)[0]!!.amount shouldBe 1
            val id = UUID.randomUUID()
            f.storage.lock(f.city, id)
            val receipt = transaction(id, f.city, plan).validated()
            f.storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            f.storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            f.contents.getValue(first)[0] shouldBe null
            f.contents.getValue(late)[0] shouldBe null
            f.storage.unlock(id)
        }
    }

    test("background debit drops stale and removed candidates and rechecks refilled slots without starvation") {
        DebitWindowFixture(city).use { f ->
            val first = f.positions[10]
            val late = f.positions[200]
            f.contents.getValue(first)[0] = ItemStack(Material.OAK_LOG, 1)
            f.contents.getValue(late)[0] = ItemStack(Material.OAK_LOG, 1)
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            val custom = named(Material.OAK_LOG, 64)
            f.contents.getValue(first)[0] = custom
            f.clearReads()
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            f.assertBounded()
            f.contents.getValue(first)[0] shouldBe custom
            f.contents.getValue(late)[0]!!.amount shouldBe 1
            // An externally removed index member cannot stay eligible merely because an earlier window found it.
            f.excluded += late
            f.storage.refreshBuilding(f.city)
            f.contents.getValue(first)[0] = ItemStack(Material.OAK_LOG, 3)
            f.clearReads()
            val plan = requireNotNull(f.prepare(mapOf("OAK_LOG" to 2L)))
            f.assertBounded()
            (late in f.reads) shouldBe false
            plan.changes.single().let {
                it.container shouldBe first
                it.before shouldBe CivPlainItem("OAK_LOG", 3)
                it.after shouldBe CivPlainItem("OAK_LOG", 1)
            }
        }
    }

    test("background debit advances through unloaded windows and resets candidates and cursor on reload and removal") {
        DebitWindowFixture(city).use { f ->
            val first = f.positions[10]
            val late = f.positions[200]
            f.contents.getValue(first)[0] = ItemStack(Material.OAK_LOG, 1)
            f.contents.getValue(late)[0] = ItemStack(Material.OAK_LOG, 1)
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            f.loaded = false
            f.clearReads()
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            f.reads shouldBe emptyMap()
            f.chunkChecks shouldBe 128 // Unloaded candidates consume budget and never cause a chunk load.
            f.loaded = true
            f.storage.replaceCities(listOf(f.city))
            f.clearReads()
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            f.assertBounded(128)
            (late in f.reads) shouldBe false
            f.storage.removeCity(f.city.id)
            f.clearReads()
            f.prepare(mapOf("OAK_LOG" to 2L)) shouldBe null
            f.assertBounded(128)
            (late in f.reads) shouldBe false
        }
    }

    test("background debit includes all 128 snow-layer units in its candidate revalidation budget") {
        DebitWindowFixture(city).use { f ->
            f.positions.take(128).forEach { f.contents.getValue(it)[0] = ItemStack(Material.SNOW, 1) }
            val plan = requireNotNull(f.prepare(mapOf("SNOW" to 128L)))
            f.assertBounded(128)
            plan.changes.size shouldBe 128
            plan.changes.all { it.before == CivPlainItem("SNOW", 1) && it.after == null } shouldBe true
        }
    }

    test("default prototype comparison excludes display PDC enchantment and damage without spending custom items") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val display = named(Material.OAK_LOG, 12)
        val tagged = ItemStack(Material.OAK_LOG, 12).also { item ->
            val meta = item.itemMeta
            meta.persistentDataContainer.set(NamespacedKey(plugin, "custom_resource"), PersistentDataType.STRING, "retained")
            item.itemMeta = meta
        }
        val enchanted = ItemStack(Material.OAK_LOG, 12).also { it.addUnsafeEnchantment(Enchantment.UNBREAKING, 1) }
        val damaged = ItemStack(Material.IRON_PICKAXE).also { item ->
            val meta = item.itemMeta as Damageable
            meta.damage = 1
            item.itemMeta = meta
        }
        val custom = listOf(display, tagged, enchanted, damaged)
        custom.forEachIndexed { index, item -> barrel.setItem(index + 1, item) }
        storage.available(city) shouldBe mapOf("OAK_LOG" to 4L)
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val debit = storage.prepareDebit(city, mapOf("OAK_LOG" to 4L))
        storage.apply(transaction(id, city, debit)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe null
        custom.forEachIndexed { index, item -> barrel.getItem(index + 1) shouldBe item }
    }

    test("partial receipt recovery finishes only known states and duplicate application consumes nothing else") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 3))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 5L))
        barrel.setItem(0, null) // A crash after the first journalled slot write.
        val receipt = transaction(id, city, plan)
        storage.lock(receipt) // Recovery can know the committed revision while this claim began before it.
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        chest.getItem(0) shouldBe null
        requireNotNull(receipt.after.inventory).materials shouldBe mapOf("OAK_LOG" to 3L)
    }

    test("a custom item introduced after capture conflicts before any other planned slot is changed") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 3))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 5L))
        val custom = named(Material.OAK_LOG, 2)
        chest.setItem(0, custom)
        val result = storage.apply(transaction(id, city, plan)) as CivInventoryApplyResult.Conflict
        result.values["reason"] shouldBe "slot-drift"
        result.values["x"] shouldBe "1"
        result.values["slot"] shouldBe "0"
        barrel.getItem(0)?.amount shouldBe 3
        chest.getItem(0) shouldBe custom
    }

    test("physical credit requires full capacity and leaves a partly fillable stack unchanged on rejection") {
        for (slot in 0..26) {
            barrel.setItem(slot, ItemStack(Material.DIRT, 64))
            chest.setItem(slot, ItemStack(Material.DIRT, 64))
        }
        barrel.setItem(0, ItemStack(Material.BREAD, 63))
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareCredit(city, "BREAD", 2) }.reason shouldBe "storage-full"
        barrel.getItem(0)?.amount shouldBe 63
        chest.getItem(0)?.type shouldBe Material.DIRT
    }

    test("building warehouse total capacity bounds deposits and withdrawal also exposes the starting reserve") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL, materials = mapOf("BREAD" to 24L),
            buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 1_720L))))
        barrel.setItem(0, ItemStack(Material.BREAD, 16))
        val custom = named(Material.COBBLESTONE, 5)
        barrel.setItem(1, custom)
        val depositId = UUID.randomUUID()
        storage.lock(city, depositId)
        val deposit = storage.depositFromControl(city, building)
        deposit.inventoryAfter.materials shouldBe mapOf("BREAD" to 24L)
        deposit.inventoryAfter.buildingStock(building.id) shouldBe mapOf("OAK_LOG" to 1_720L, "BREAD" to 8L)
        storage.apply(transaction(depositId, city, deposit)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)?.amount shouldBe 8
        barrel.getItem(1) shouldBe custom
        storage.unlock(depositId)
        city = city.copy(inventory = deposit.inventoryAfter, revision = city.revision + 1)
        val withdrawalId = UUID.randomUUID()
        storage.lock(city, withdrawalId)
        val withdrawal = storage.withdrawToControl(city, building, "BREAD", 16)
        withdrawal.inventoryAfter.buildingStock(building.id) shouldBe mapOf("OAK_LOG" to 1_720L)
        withdrawal.inventoryAfter.materials shouldBe mapOf("BREAD" to 16L)
        storage.apply(transaction(withdrawalId, city, withdrawal)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)?.amount shouldBe 24
    }

    test("virtual availability and debit combine reserve with building warehouses while ignoring native cargo") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL,
            buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 5L))))
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 64))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 8L)
        storage.lock(city, UUID.randomUUID())
        val debit = storage.prepareDebit(city, mapOf("OAK_LOG" to 6L))
        debit.inventoryAfter.materials shouldBe emptyMap()
        debit.inventoryAfter.buildingStock(building.id) shouldBe mapOf("OAK_LOG" to 2L)
        debit.changes shouldBe emptyList()
        barrel.getItem(0)?.amount shouldBe 64
    }

    test("virtual production needs its building and counts every resource toward that building capacity") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL,
            buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 1_700L, "COBBLESTONE" to 20L))))
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareCredit(city, "BREAD", 1) }.reason shouldBe "storage-building-required"
        shouldThrow<CivsRejection> { storage.prepareCredit(city, "BREAD", 9, building.id) }.reason shouldBe "storage-full"
        val credit = storage.prepareCredit(city, "BREAD", 8, building.id)
        credit.inventoryAfter.buildingStock(building.id)["BREAD"] shouldBe 8L
        credit.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 3L)
        credit.changes shouldBe emptyList()
    }

    test("unloaded containers retry without loading chunks and replacement by another block conflicts") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 3))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val receipt = transaction(id, city, storage.prepareDebit(city, mapOf("OAK_LOG" to 3L)))
        world.unloadChunk(0, 0)
        (storage.apply(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        world.isChunkLoaded(0, 0) shouldBe false
        world.loadChunk(0, 0)
        world.getBlockAt(0, 64, 0).type = Material.STONE
        (storage.apply(receipt) is CivInventoryApplyResult.Conflict) shouldBe true
    }

    test("unloaded physical sources never spend an invisible reserve or load their chunk") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 64))
        world.unloadChunk(0, 0)
        storage.available(city) shouldBe emptyMap()
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareDebit(city, mapOf("OAK_LOG" to 3L)) }.reason shouldBe "materials-missing"
        storage.prepareMaterialization(city).inventoryAfter shouldBe city.inventory
        world.isChunkLoaded(0, 0) shouldBe false
    }

    test("locking closes viewers and blocks clicks and hopper moves until explicit unlock") {
        val player = paper.addPlayer("StorageViewer")
        player.teleport(Location(world, 0.5, 65.0, 0.5))
        val view = requireNotNull(player.openInventory(barrel))
        storage.hasViewers(city) shouldBe true
        val id = UUID.randomUUID()
        storage.lock(city, id)
        (player.openInventory.topInventory === barrel) shouldBe false
        // MockBukkit leaves the default view's top inventory null after close; Paper supplies a crafting inventory.
        player.openInventory(paper.server.createInventory(player, 9))
        storage.hasViewers(city) shouldBe false
        val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        storage.onClick(click)
        click.isCancelled shouldBe true
        val hopper = InventoryMoveItemEvent(barrel, ItemStack(Material.OAK_LOG), chest, true)
        storage.onMove(hopper)
        hopper.isCancelled shouldBe true
        storage.unlock(id)
        val unlocked = InventoryMoveItemEvent(barrel, ItemStack(Material.OAK_LOG), chest, true)
        storage.onMove(unlocked)
        unlocked.isCancelled shouldBe false
        val foreign = world.getBlockAt(8, 64, 8).also { it.type = Material.BARREL }
        player.openInventory(cargo(foreign))
        storage.hasViewers(city) shouldBe false
    }

    test("a registered chest never includes an unregistered adjacent half") {
        world.loadChunk(0, -1)
        world.getBlockAt(1, 64, -1).type = Material.CHEST
        val foreign = (world.getBlockAt(1, 64, -1).state as Chest).blockInventory
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        foreign.setItem(0, ItemStack(Material.OAK_LOG, 64))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 2L)
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareDebit(city, mapOf("OAK_LOG" to 3L)) }.reason shouldBe "materials-missing"
        foreign.getItem(0)?.amount shouldBe 64
    }

    test("all indexed chest types and barrels inside the full 3D bounds participate beyond the former 32 container limit") {
        val hall = city.buildings.single().copy(bounds = CivBox(CivPosition(2, 64, 0), CivPosition(11, 66, 3)))
        city = city.copy(buildings = listOf(hall))
        repeat(40) { i ->
            val block = world.getBlockAt(2 + i % 10, 64, i / 10)
            block.type = listOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)[i % 3]
            indexedBlocks += block
            cargo(block).setItem(0, ItemStack(Material.OAK_LOG))
        }
        listOf(CivPosition(2, 63, 0), CivPosition(2, 67, 0), CivPosition(12, 64, 0)).forEach { position ->
            val block = world.getBlockAt(position.x, position.y, position.z)
            block.type = Material.BARREL
            indexedBlocks += block
            cargo(block).setItem(0, ItemStack(Material.OAK_LOG, 64))
        }
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 2))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 3))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 45L)
        scans shouldBe 1
        repeat(4) { storage.available(city) shouldBe mapOf("OAK_LOG" to 45L) }
        storage.updateCity(city.copy(revision = city.revision + 1))
        scans shouldBe 1
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 45L))
        plan.changes.size shouldBe 42
        plan.changes.first().container shouldBe control
        storage.apply(transaction(id, city, plan).validated()) shouldBe CivInventoryApplyResult.Applied
        storage.available(city) shouldBe emptyMap()
        cargo(world.getBlockAt(2, 63, 0)).getItem(0)?.amount shouldBe 64
    }

    test("each adjacent indexed chest half is counted once even when also manually registered") {
        val first = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }
        val second = world.getBlockAt(3, 64, 0).also { it.type = Material.CHEST }
        indexedBlocks += listOf(first, second)
        city = city.copy(inventory = requireNotNull(city.inventory).copy(containers = listOf(registered, CivPosition(2, 64, 0))))
        cargo(first).setItem(0, ItemStack(Material.OAK_LOG, 2))
        cargo(second).setItem(0, ItemStack(Material.OAK_LOG, 7))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 9L)
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val receipt = transaction(id, city, storage.prepareDebit(city, mapOf("OAK_LOG" to 9L))).validated()
        receipt.changes.map { it.container }.toSet() shouldBe setOf(CivPosition(2, 64, 0), CivPosition(3, 64, 0))
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        cargo(first).isEmpty shouldBe true
        cargo(second).isEmpty shouldBe true
    }

    test("production fills the requested building control before another control or matching chest stack") {
        val otherControl = CivPosition(6, 64, 0)
        val other = city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
            anchor = otherControl, entrance = CivPosition(6, 64, 3), control = otherControl,
            bounds = CivBox(CivPosition(6, 64, 0), CivPosition(8, 65, 2)))
        city = city.copy(buildings = city.buildings + other)
        val block = world.getBlockAt(6, 64, 0).also { it.type = Material.BARREL }
        chest.setItem(0, ItemStack(Material.BREAD, 63))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val credit = storage.prepareCredit(city, "BREAD", 2, other.id)
        credit.changes.map { it.container }.distinct() shouldBe listOf(otherControl)
        storage.apply(transaction(id, city, credit)) shouldBe CivInventoryApplyResult.Applied
        cargo(block).getItem(0)?.amount shouldBe 2
        barrel.isEmpty shouldBe true
        chest.getItem(0)?.amount shouldBe 63
    }

    test("partial materialization retains full-storage leftovers and replay never grants twice") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(
            buildingMaterials = mapOf(building.id.toString() to mapOf("BREAD" to 5L))))
        repeat(27) { slot ->
            barrel.setItem(slot, ItemStack(Material.DIRT, 64))
            chest.setItem(slot, ItemStack(Material.DIRT, 64))
        }
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 63))
        chest.setItem(0, ItemStack(Material.BREAD, 60))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareMaterialization(city)
        plan.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 2L)
        plan.inventoryAfter.buildingStock(building.id) shouldBe mapOf("BREAD" to 1L)
        plan.changes.size shouldBe 2
        barrel.getItem(0)?.amount shouldBe 63
        chest.getItem(0)?.amount shouldBe 60
        val receipt = transaction(id, city, plan).validated()
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)?.amount shouldBe 64
        chest.getItem(0)?.amount shouldBe 64
        storage.unlock(id)
        city = receipt.after
        storage.lock(city, UUID.randomUUID())
        val full = storage.prepareMaterialization(city)
        full.inventoryAfter shouldBe city.inventory
        full.changes shouldBe emptyList()
    }

    test("switching virtual to physical is one materialization plan and physical deposit cannot hide cargo") {
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareMaterialization(city)
        plan.inventoryAfter.mode shouldBe CivStorageMode.PHYSICAL
        plan.inventoryAfter.materials shouldBe emptyMap()
        barrel.isEmpty shouldBe true
        val receipt = transaction(id, city, plan)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.unlock(id)
        city = receipt.after
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.depositFromControl(city, city.buildings.single()) }.reason shouldBe "invalid-storage-mode"
        barrel.getItem(0)?.amount shouldBe 3
    }

    test("materialization stops at 1024 changed slots and keeps every untransferred item") {
        city = city.copy(buildings = listOf(city.buildings.single().copy(bounds = CivBox(CivPosition(2, 64, 0), CivPosition(11, 65, 3)))),
            inventory = requireNotNull(city.inventory).copy(materials = mapOf("OAK_LOG" to 100_000L)))
        repeat(40) { i -> indexedBlocks += world.getBlockAt(2 + i % 10, 64, i / 10).also { it.type = Material.BARREL } }
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareMaterialization(city)
        plan.changes.size shouldBe 1_024
        plan.changes.sumOf { it.after!!.amount } shouldBe 65_536
        plan.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 34_464L)
        barrel.isEmpty shouldBe true
        transaction(id, city, plan).validated()
    }

    test("materialization examines at most 128 containers once and advances past a full no-op window") {
        city = city.copy(buildings = listOf(city.buildings.single().copy(
            bounds = CivBox(CivPosition(2, 64, 0), CivPosition(15, 65, 9)))),
            inventory = requireNotNull(city.inventory).copy(materials = mapOf("OAK_LOG" to 3L, "BREAD" to 5L, "COBBLESTONE" to 7L),
                buildingMaterials = mapOf(city.buildings.single().id.toString() to mapOf("WHEAT" to 2L))))
        val automatic = (2..15).flatMap { x -> (0..9).map { z -> CivPosition(x, 64, z) } }
        val positions = listOf(control) + automatic + registered
        val free = automatic.last()
        val resolveReads = mutableMapOf<CivPosition, Int>()
        val snapshotReads = mutableMapOf<CivPosition, Int>()
        val contents = positions.associateWith { position ->
            Array<ItemStack?>(27) { if (position == free) null else ItemStack(Material.DIRT, 64) }
        }
        // Count the native API boundary directly; Core's runtime supplies the real ItemStack/Bukkit thread contract.
        val states = positions.associateWith { position ->
            val inventory = mockk<Inventory>()
            every { inventory.size } returns 27
            every { inventory.storageContents } answers {
                snapshotReads.merge(position, 1, Int::plus)
                contents.getValue(position).copyOf()
            }
            mockk<Barrel> {
                every { type } returns Material.BARREL
                every { x } returns position.x
                every { y } returns position.y
                every { z } returns position.z
                every { this@mockk.inventory } returns inventory
            }
        }
        val blocks = states.mapValues { (_, state) -> mockk<Block> {
            every { type } returns Material.BARREL
            every { this@mockk.state } returns state
        } }
        val nativeWorld = mockk<World>()
        val chunk = mockk<Chunk> {
            every { this@mockk.world } returns nativeWorld
            every { x } returns 0
            every { z } returns 0
        }
        every { nativeWorld.uid } returns city.worldId
        every { nativeWorld.name } returns "bounded-storage"
        every { nativeWorld.minHeight } returns -64
        every { nativeWorld.maxHeight } returns 320
        every { nativeWorld.loadedChunks } returns arrayOf(chunk)
        every { nativeWorld.isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { nativeWorld.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val position = CivPosition(firstArg(), secondArg(), thirdArg())
            resolveReads.merge(position, 1, Int::plus)
            blocks.getValue(position)
        }
        val nativeServer = mockk<Server> {
            every { pluginManager } returns mockk(relaxed = true)
            every { onlinePlayers } returns emptyList()
            every { getWorld(city.worldId) } returns nativeWorld
        }
        val nativePlugin = mockk<Plugin> { every { server } returns nativeServer }
        val bounded = CivsStorage(nativePlugin) { states.values.toTypedArray() }
        try {
            var id = UUID.randomUUID()
            bounded.lock(city, id)
            val full = bounded.prepareMaterialization(city)
            full.changes shouldBe emptyList()
            full.inventoryAfter shouldBe city.inventory
            resolveReads.size shouldBe 128
            snapshotReads.size shouldBe 128
            resolveReads.values.all { it == 1 } shouldBe true
            snapshotReads.values.all { it == 1 } shouldBe true
            (free in resolveReads) shouldBe false
            bounded.unlock(id)

            resolveReads.clear(); snapshotReads.clear()
            id = UUID.randomUUID()
            bounded.lock(city, id)
            val next = bounded.prepareMaterialization(city)
            next.inventoryAfter.materials shouldBe emptyMap()
            next.inventoryAfter.buildingStock(city.buildings.single().id) shouldBe emptyMap()
            next.changes.map { it.container }.distinct() shouldBe listOf(free)
            (resolveReads.size <= 128) shouldBe true
            resolveReads[control] shouldBe 1
            resolveReads[free] shouldBe 1
            resolveReads.values.all { it == 1 } shouldBe true
            snapshotReads.values.all { it == 1 } shouldBe true
            contents.getValue(free).all { it == null } shouldBe true // Planning still makes no native changes.
            bounded.unlock(id)

            bounded.replaceCities(listOf(city))
            id = UUID.randomUUID()
            bounded.lock(city, id)
            bounded.prepareMaterialization(city).changes shouldBe emptyList() // Reload resets the ephemeral cursor.
            bounded.unlock(id)
            contents.getValue(free)[0] = ItemStack(Material.BREAD, 9)
            resolveReads.clear(); snapshotReads.clear()
            bounded.available(city)["BREAD"] shouldBe 9L
            resolveReads.size shouldBe positions.size // Explicit availability remains exact, not windowed.
        } finally { bounded.close() }
    }

    test("auto receipt recovery locks durable positions while unloaded and rejects coordinates outside building height") {
        val automatic = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }
        indexedBlocks += automatic
        cargo(automatic).setItem(0, ItemStack(Material.OAK_LOG, 4))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val receipt = transaction(id, city, storage.prepareDebit(city, mapOf("OAK_LOG" to 4L))).validated()
        storage.unlock(id)
        val chunk = world.getChunkAt(0, 0)
        storage.indexChunkUnload(ChunkUnloadEvent(chunk))
        world.unloadChunk(0, 0)
        storage.removeCity(city.id)
        storage.lock(receipt)
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        world.isChunkLoaded(0, 0) shouldBe false
        world.loadChunk(0, 0)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        cargo(automatic).isEmpty shouldBe true
        val forged = receipt.copy(changes = listOf(receipt.changes.single().copy(container = CivPosition(2, 63, 0))))
        shouldThrow<IllegalArgumentException> { forged.validated() }
    }

    test("place and break update cached containers without scanning and new sources wait for the next claim") {
        storage.updateCity(city)
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val player = paper.addPlayer("StorageBuilder")
        player.openInventory(paper.server.createInventory(player, 9))
        val block = world.getBlockAt(2, 64, 0)
        val before = block.state
        block.type = Material.BARREL
        cargo(block).setItem(0, ItemStack(Material.OAK_LOG, 4))
        paper.callEvent(BlockPlaceEvent(block, before, block.getRelative(BlockFace.DOWN), ItemStack(Material.BARREL), player, true, EquipmentSlot.HAND))
        shouldThrow<CivsRejection> { storage.prepareDebit(city, mapOf("OAK_LOG" to 1L)) }.reason shouldBe "materials-missing"
        storage.unlock(id)
        storage.available(city) shouldBe mapOf("OAK_LOG" to 4L)
        scans shouldBe 1
        val next = UUID.randomUUID()
        storage.lock(city, next)
        paper.callEvent(BlockBreakEvent(block, player)).isCancelled shouldBe true
        storage.unlock(next)
        paper.callEvent(BlockBreakEvent(block, player)).isCancelled shouldBe false
        block.type = Material.AIR
        storage.available(city) shouldBe emptyMap()
        scans shouldBe 1
    }

    test("paste refresh and chunk reload discover native changes without a per-query scan") {
        storage.updateCity(city)
        val block = world.getBlockAt(2, 64, 0).also { it.type = Material.TRAPPED_CHEST }
        indexedBlocks += block
        cargo(block).setItem(0, ItemStack(Material.BREAD, 5))
        storage.available(city) shouldBe emptyMap()
        storage.refreshBuilding(city, city.buildings.single())
        scans shouldBe 2
        storage.available(city) shouldBe mapOf("BREAD" to 5L)
        val chunk = world.getChunkAt(0, 0)
        storage.indexChunkUnload(ChunkUnloadEvent(chunk))
        world.unloadChunk(0, 0)
        storage.available(city) shouldBe emptyMap()
        world.isChunkLoaded(0, 0) shouldBe false
        world.loadChunk(0, 0)
        storage.indexChunkLoad(ChunkLoadEvent(world.getChunkAt(0, 0), false))
        storage.available(city) shouldBe mapOf("BREAD" to 5L)
        scans shouldBe 3
    }

    test("explosions retain claimed containers and remove only destroyed unlocked index entries") {
        val block = world.getBlockAt(2, 64, 0).also { it.type = Material.BARREL }
        indexedBlocks += block
        cargo(block).setItem(0, ItemStack(Material.BREAD, 5))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        fun explosion() = BlockExplodeEvent(world.getBlockAt(3, 64, 0), world.getBlockAt(3, 64, 0).state,
            mutableListOf<Block>(block), 0f, ExplosionResult.DESTROY)
        paper.callEvent(explosion()).blockList() shouldBe emptyList()
        storage.available(city) shouldBe mapOf("BREAD" to 5L)
        storage.unlock(id)
        paper.callEvent(explosion()).blockList() shouldBe listOf(block)
        block.type = Material.AIR
        storage.available(city) shouldBe emptyMap()
        scans shouldBe 1
    }

    test("receipt validation permits geometry-authorized auto slots but rejects oversized atomic receipts") {
        city = city.copy(radius = 100, buildings = listOf(city.buildings.single().copy(
            bounds = CivBox(CivPosition(2, 64, 0), CivPosition(61, 65, 61)))))
        val changes = (0..MAX_INVENTORY_SLOT_CHANGES).map { i ->
            val container = i / 27
            CivInventorySlotChange(CivPosition(2 + container % 60, 64, container / 60), "CHEST", i % 27,
                CivPlainItem("OAK_LOG", 1), null)
        }
        val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
            city.copy(revision = city.revision + 1), changes.take(MAX_INVENTORY_SLOT_CHANGES))
        receipt.validated().changes.size shouldBe MAX_INVENTORY_SLOT_CHANGES
        shouldThrow<IllegalArgumentException> { receipt.copy(changes = changes).validated() }
    }

    test("native storage entrypoints reject async access before touching inventories") {
        val failure = shouldThrow<CompletionException> {
            CompletableFuture.supplyAsync { storage.available(city) }.join()
        }
        (failure.cause is IllegalStateException) shouldBe true
        shouldThrow<CompletionException> { CompletableFuture.runAsync { storage.lock(city, UUID.randomUUID()) }.join() }
        barrel.isEmpty shouldBe true
    }
})

private fun transaction(id: UUID, city: Settlement, plan: CivStoragePlan) = CivInventoryTransaction(
    id, city.id, city.revision, city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes, plan.playerDebit,
)

private fun named(material: Material, count: Int): ItemStack = ItemStack(material, count).also { item ->
    val meta = item.itemMeta
    meta.displayName(Component.text("Custom cargo"))
    item.itemMeta = meta
}

private fun cargo(block: Block): Inventory = when (val state = block.state) {
    is Barrel -> state.inventory
    is Chest -> state.blockInventory
    else -> error("Test container missing at ${block.location}")
}

/** Count just the native inventory boundary; Bukkit and ItemStacks still come from Core's test runtime. */
private class DebitWindowFixture(base: Settlement) : AutoCloseable {
    val city = base.copy(buildings = listOf(base.buildings.single().copy(
        bounds = CivBox(CivPosition(2, 64, 0), CivPosition(15, 65, 15)))))
    val positions = listOf(CivPosition(0, 64, 0)) + (2..15).flatMap { x ->
        (64..65).flatMap { y -> (0..15).map { z -> CivPosition(x, y, z) } }
    }.take(260) + CivPosition(1, 64, 0)
    val contents = positions.associateWith { Array<ItemStack?>(27) { ItemStack(Material.DIRT, 64) } }
    val reads = mutableMapOf<CivPosition, Int>()
    private val snapshots = mutableMapOf<CivPosition, Int>()
    val excluded = mutableSetOf<CivPosition>()
    var loaded = true
    var chunkChecks = 0
    val storage: CivsStorage

    init {
        val nativeWorld = mockk<World>()
        val states = positions.associateWith { position ->
            val inventory = mockk<Inventory>()
            every { inventory.size } returns 27
            every { inventory.storageContents } answers {
                snapshots.merge(position, 1, Int::plus)
                contents.getValue(position).copyOf()
            }
            every { inventory.getItem(any()) } answers { contents.getValue(position)[firstArg<Int>()] }
            every { inventory.setItem(any(), any()) } answers {
                contents.getValue(position)[firstArg<Int>()] = secondArg<ItemStack?>()
            }
            mockk<Barrel> {
                every { type } returns Material.BARREL
                every { x } returns position.x
                every { y } returns position.y
                every { z } returns position.z
                every { this@mockk.inventory } returns inventory
            }
        }
        val blocks = states.mapValues { (_, state) -> mockk<Block> {
            every { type } returns Material.BARREL
            every { this@mockk.state } returns state
        } }
        val chunk = mockk<Chunk> {
            every { world } returns nativeWorld
            every { x } returns 0
            every { z } returns 0
        }
        every { nativeWorld.uid } returns city.worldId
        every { nativeWorld.name } returns "bounded-debit"
        every { nativeWorld.minHeight } returns -64
        every { nativeWorld.maxHeight } returns 320
        every { nativeWorld.loadedChunks } returns arrayOf(chunk)
        every { nativeWorld.isChunkLoaded(any<Int>(), any<Int>()) } answers { chunkChecks++; loaded }
        every { nativeWorld.getChunkAt(any<Int>(), any<Int>()) } answers {
            check(loaded) { "Background debit must not load chunks" }
            chunk
        }
        every { nativeWorld.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val position = CivPosition(firstArg(), secondArg(), thirdArg())
            reads.merge(position, 1, Int::plus)
            blocks.getValue(position)
        }
        val nativeServer = mockk<Server> {
            every { pluginManager } returns mockk(relaxed = true)
            every { onlinePlayers } returns emptyList()
            every { getWorld(city.worldId) } returns nativeWorld
        }
        val nativePlugin = mockk<Plugin> { every { server } returns nativeServer }
        storage = CivsStorage(nativePlugin) { states.filterKeys { it !in excluded }.values.toTypedArray<BlockState>() }
    }

    fun prepare(required: Map<String, Long>): CivStoragePlan? {
        val id = UUID.randomUUID()
        storage.lock(city, id)
        return try { storage.prepareDebitBackground(city, required) } finally { storage.unlock(id) }
    }

    fun assertBounded(exact: Int? = null) {
        (reads.size <= 128) shouldBe true
        reads.values.all { it == 1 } shouldBe true
        snapshots shouldBe reads
        if (exact != null) reads.size shouldBe exact
    }

    fun clearReads() { reads.clear(); snapshots.clear(); chunkChecks = 0 }
    override fun close() = storage.close()
}
