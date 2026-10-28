package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.bukkit.block.DoubleChest
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

    test("Auto grants recheck native permission after the durable journal acknowledgement") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL))
        var allowed = true
        storage.foodCanModify = { _, _ -> allowed }
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareAutoSupply(city, CivAutoSupplyDemand(city.buildings.single().id, mapOf("BREAD" to 4L))) { allowed }!!
        val tx = CivInventoryTransaction(id, city.id, city.revision,
            city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes,
            autoSupplyBuildingId = plan.autoSupplyBuildingId)
        storage.seal(tx)
        allowed = false // The journal write completed after Lands authority changed.
        (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        barrel.isEmpty shouldBe true
        storage.unlock(id)
    }

    test("Auto grant permission checks all untouched destinations before the first write") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(registered)),
            buildings = city.buildings.map { it.copy(phase = BuildingPhase.ACTIVE,
                bounds = CivBox(CivPosition(0, 64, 0), CivPosition(4, 65, 2))) })
        barrel.setItem(0, ItemStack(Material.BREAD, 2))
        val id = UUID.randomUUID()
        val tx = CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1), listOf(
            CivInventorySlotChange(control, "BARREL", 0, CivPlainItem("BREAD", 2), CivPlainItem("BREAD", 4)),
            CivInventorySlotChange(registered, "CHEST", 0, null, CivPlainItem("STONE", 1))),
            autoSupplyBuildingId = city.buildings.single().id)
        storage.reserve(city, id); storage.seal(tx)
        storage.foodCanModify = { _, point -> point != registered }
        val result = storage.reconcile(tx) as CivInventoryApplyResult.Conflict
        result.values["reason"] shouldBe "lands-denied"
        barrel.getItem(0)!!.amount shouldBe 2
        chest.isEmpty shouldBe true
        storage.unlock(id)
    }

    test("partial Auto grant recovery keeps owned credits and rechecks only remaining slots") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL))
        val id = UUID.randomUUID()
        val tx = CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1), listOf(
            CivInventorySlotChange(control, "BARREL", 0, null, CivPlainItem("BREAD", 4)),
            CivInventorySlotChange(control, "BARREL", 1, null, CivPlainItem("STONE", 1))),
            autoSupplyBuildingId = city.buildings.single().id)
        storage.reserve(city, id); storage.seal(tx)
        barrel.setItem(0, ItemStack(Material.BREAD, 4)) // A crash persisted only the first accepted slot.
        storage.foodCanModify = { _, _ -> false }
        (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        barrel.getItem(0)!!.amount shouldBe 4
        barrel.getItem(1) shouldBe null
        storage.unlock(id); storage.lock(tx)
        storage.foodCanModify = { _, _ -> true }
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.foodCanModify = { _, _ -> error("Fully applied native grants need no new authority") }
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)!!.amount shouldBe 4
        barrel.getItem(1)!!.amount shouldBe 1
        storage.unlock(id)
    }

    test("typed Auto grant receipts reject transfers foreign scope and city mutations") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(registered)))
        val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, city.copy(revision = city.revision + 1),
            listOf(CivInventorySlotChange(control, "BARREL", 0, null, CivPlainItem("BREAD", 4))),
            autoSupplyBuildingId = city.buildings.single().id).validated()
        listOf(tx.copy(after = tx.after.copy(autonomy = null)),
            tx.copy(changes = listOf(tx.changes.single().copy(before = CivPlainItem("BREAD", 5)))),
            tx.copy(changes = listOf(tx.changes.single().copy(before = CivPlainItem("STONE", 1)))),
            tx.copy(changes = listOf(tx.changes.single().copy(container = registered, containerType = "CHEST"))),
            tx.copy(autoSupplyBuildingId = UUID.randomUUID()),
            tx.copy(worldChanges = listOf(CivPlanBlock(control, "minecraft:air", "minecraft:stone"))),
            tx.copy(playerDebit = CivPlayerDebit(city.owner, listOf(CivPlayerSlotChange(0, CivPlainItem("BREAD", 4), null))))
        ).forEach { shouldThrow<IllegalArgumentException> { it.validated() } }
        storage.reserve(city, tx.id)
        shouldThrow<IllegalArgumentException> { storage.seal(tx.copy(after = tx.after.copy(name = "Changed"))) }
        storage.unlock(tx.id)
    }

    test("Auto supply marker survives the real journal codec while older missing markers stay readable") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL))
        val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, city.copy(revision = city.revision + 1),
            listOf(CivInventorySlotChange(control, "BARREL", 0, null, CivPlainItem("BREAD", 4))),
            autoSupplyBuildingId = city.buildings.single().id).validated()
        val root = java.nio.file.Files.createTempDirectory("civs-auto-supply-codec-")
        try {
            CivsStore(root, java.util.concurrent.Executor(Runnable::run)).use { store ->
                store.saveInventoryTransaction(tx).join() shouldBe tx
                store.loadInventoryTransactions().join().single() shouldBe tx
            }
            val path = root.resolve("inventory-transactions/${tx.id}.json")
            val document = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).asJsonObject
            document.getAsJsonObject("transaction").remove("autoSupplyBuildingId")
            java.nio.file.Files.writeString(path, com.google.gson.Gson().toJson(document))
            CivsStore(root, java.util.concurrent.Executor(Runnable::run)).use { store ->
                store.loadInventoryTransactions().join().single() shouldBe tx.copy(autoSupplyBuildingId = null)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    test("Auto top-up fills exact native deficit once and rejects ordinary or denied grants") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL))
        storage.foodCanModify = { _, _ -> true }
        val hall = city.buildings.single()
        barrel.setItem(0, ItemStack(Material.BREAD, 2))
        val demand = CivAutoSupplyDemand(hall.id, mapOf("BREAD" to 4L))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        shouldThrow<CivsRejection> { storage.prepareAutoSupply(city, demand) { false } }.reason shouldBe "lands-denied"
        barrel.getItem(0)!!.amount shouldBe 2
        shouldThrow<CivsRejection> { storage.prepareAutoSupply(city.copy(autonomy = null), demand) { true } }.reason shouldBe "not-authorized"
        val plan = storage.prepareAutoSupply(city, demand) { true }!!
        val tx = CivInventoryTransaction(id, city.id, city.revision,
            city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes,
            autoSupplyBuildingId = plan.autoSupplyBuildingId)
        storage.seal(tx)
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)!!.amount shouldBe 4
        storage.unlock(id)
        city = tx.after
        val second = UUID.randomUUID()
        storage.reserve(city, second)
        storage.prepareAutoSupply(city, demand) { true } shouldBe null
        storage.unlock(second)
    }

    test("a full Auto container keeps the grant unissued and migration reserves are not available") {
        city = city.copy(autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL,
            materials = mapOf("BREAD" to 9L)))
        storage.available(city)["BREAD"] shouldBe null
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareMaterialization(city)
        val tx = CivInventoryTransaction(id, city.id, city.revision,
            city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes)
        storage.seal(tx)
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.unlock(id)
        city = tx.after
        storage.available(city)["BREAD"] shouldBe 9L
        repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        val second = UUID.randomUUID()
        storage.reserve(city, second)
        storage.prepareAutoSupply(city, CivAutoSupplyDemand(city.buildings.single().id, mapOf("BREAD" to 4L))) { true } shouldBe null
        storage.unlock(second)
        barrel.contents.filterNotNull().sumOf(ItemStack::getAmount) shouldBe 27 * 64
    }

    test("Auto producer reserves materialize in all own workplace containers and never overflow to the hall") {
        val point = CivPosition(10, 64, 0)
        val forest = city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = point, bounds = CivBox(point.copy(x = 8, y = 63, z = -2), point.copy(x = 12, y = 67, z = 2)),
            entrance = point.copy(z = -3), phase = BuildingPhase.ACTIVE, control = point)
        world.getBlockAt(10, 64, 0).type = Material.BARREL
        world.getBlockAt(11, 64, 0).type = Material.CHEST
        indexedBlocks += world.getBlockAt(11, 64, 0)
        val localBarrel = (world.getBlockAt(10, 64, 0).state as Barrel).inventory
        val localChest = (world.getBlockAt(11, 64, 0).state as Chest).blockInventory
        repeat(localBarrel.size) { localBarrel.setItem(it, ItemStack(Material.STONE, 64)) }
        city = CivsRules.validate(city.copy(autonomy = CivAutonomy(), buildings = city.buildings + forest,
            inventory = CivInventory(CivStorageMode.PHYSICAL,
                buildingMaterials = mapOf(forest.id.toString() to mapOf("OAK_LOG" to 5L)))))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareMaterialization(city)
        plan.changes.map { it.container }.toSet() shouldBe setOf(CivPosition(11, 64, 0))
        val tx = CivInventoryTransaction(id, city.id, city.revision,
            city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes)
        storage.seal(tx)
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        localChest.getItem(0)!!.amount shouldBe 5
        barrel.isEmpty shouldBe true
        storage.unlock(id)
        repeat(localChest.size) { localChest.setItem(it, ItemStack(Material.STONE, 64)) }
        city = tx.after.copy(inventory = tx.after.inventory!!.copy(
            buildingMaterials = mapOf(forest.id.toString() to mapOf("OAK_LOG" to 5L))))
        val blockedId = UUID.randomUUID()
        storage.reserve(city, blockedId)
        val blocked = storage.prepareMaterialization(city)
        blocked.changes shouldBe emptyList()
        blocked.inventoryAfter.buildingStock(forest.id) shouldBe mapOf("OAK_LOG" to 5L)
        barrel.isEmpty shouldBe true
        storage.unlock(blockedId)
    }

    for (autonomous in listOf(false, true)) test("resurrection receipts debit one exact tagged scroll without synthetic stock autonomous=$autonomous") {
        var persisted = 0
        storage.close()
        storage = CivsStorage(plugin, PaperPlayerDataPersistence { persisted++ }) { emptyArray() }
        val player = paper.addPlayer("ScrollOwner").apply { addAttachment(plugin, "arccivs.admin", autonomous) }
        val worker = CivWorker(UUID.randomUUID(), "Fallen", null, skills = mapOf("FORESTER" to 10L),
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("BREAD", 2) else null })
        val corpse = CivsCorpses.create(worker, CivDeathContext(CivPosition(5, 64, 5), 2_000))
        city = city.copy(owner = player.uniqueId, stock = if (autonomous) CivsRules.STARTING_STOCK else Stock(),
            inventory = if (autonomous) null else city.inventory, autonomy = if (autonomous) CivAutonomy() else null,
            corpses = listOf(corpse))
        val scroll = CivsResurrectionScroll.create(CivsText.fromYaml(civsLocaleSource()), player).apply { amount = 3 }
        val before = requireNotNull(CivResidentItem.of(scroll))
        val debit = CivResurrectionDebit(player.uniqueId, corpse, CivNativePlayerSlot(0, before, before.copy(amount = 2)),
            3_000, corpse.position, 64.0)
        val after = CivsRules.resurrect(city, player.uniqueId, worker.id, corpse.diedAt, 3_000, administrator = autonomous)
        val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(), resurrection = debit).validated()
        player.inventory.setItem(0, scroll.clone())
        storage.reserve(city, receipt.id, player)
        storage.seal(receipt)
        player.inventory.setItem(0, scroll.clone().apply { editMeta { it.displayName(Component.text("Changed scroll")) } })
        (storage.reconcile(receipt) is CivInventoryApplyResult.Conflict) shouldBe true
        player.inventory.getItem(0)!!.amount shouldBe 3
        player.inventory.setItem(0, scroll.clone())
        storage.resurrectionDebitState(receipt) shouldBe null
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.resurrectionDebitState(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe scroll.clone().apply { amount = 2 }
        persisted shouldBe 2 // Recovery persists the same after-state, without a second debit.
        receipt.after.inventory shouldBe city.inventory
        receipt.after.stock shouldBe city.stock
        shouldThrow<IllegalArgumentException> { receipt.copy(resurrection = debit.copy(change = debit.change.copy(after = null))).validated() }
        shouldThrow<IllegalArgumentException> { receipt.copy(changes = listOf(CivInventorySlotChange(control, "BARREL", 0,
            null, CivPlainItem("PAPER", 1)))).validated() }
    }

    test("autonomous corpse loot conserves native items without creating town inventory") {
        storage.close()
        storage = CivsStorage(plugin, PaperPlayerDataPersistence { }) { emptyArray() }
        val admin = paper.addPlayer("CorpseAdmin").apply { addAttachment(plugin, "arccivs.admin", true) }
        val pickaxe = named(Material.DIAMOND_PICKAXE, 1)
        val dead = CivWorker(UUID.randomUUID(), "Fallen", null,
            items = List(CivsEquipment.SLOTS) { if (it == 4) CivResidentItem.of(pickaxe) else null })
        val corpse = CivsCorpses.create(dead, CivDeathContext(CivPosition(5, 64, 5), 2_000))
        city = city.copy(inventory = null, stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy(), corpses = listOf(corpse))
        val id = UUID.randomUUID()
        storage.reserve(city, id, admin)
        val prepared = storage.prepareCorpseTransfer(city, dead.id, admin, 4, ClickType.LEFT)
        val after = city.copy(corpses = listOf(corpse.copy(worker = prepared.worker)), revision = city.revision + 1)
        val receipt = CivInventoryTransaction(id, city.id, city.revision, after, emptyList(), residentTransfer = prepared.transfer)
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        admin.inventory.getItem(0) shouldBe pickaxe
        after.inventory shouldBe null
        after.stock shouldBe city.stock
        storage.unlock(id)
        val stranger = paper.addPlayer("CorpseStranger")
        shouldThrow<CivsRejection> { storage.reserve(city, UUID.randomUUID(), stranger) }.reason shouldBe "not-authorized"
    }

    fun addLocalBuilding(type: BuildingType, x: Int = 6): CivBuilding {
        val position = CivPosition(x, 64, 0)
        val building = city.buildings.first().copy(id = UUID.randomUUID(), type = type, phase = BuildingPhase.ACTIVE,
            anchor = position, entrance = position.copy(z = 3), control = position,
            bounds = CivBox(position, position.copy(x = x + 2, y = 65, z = 2)))
        world.getBlockAt(x, 64, 0).type = Material.BARREL
        city = city.copy(buildings = city.buildings + building)
        return building
    }

    test("an old unpicked worker handoff cannot debit a bag with either full or empty warehouse slots") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val quarry = addLocalBuilding(BuildingType.QUARRY, 10)
        val courier = CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1)
        val producer = CivWorker(UUID.randomUUID(), "Miner", quarry.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("COAL", 64) else null })
        city = city.copy(workers = listOf(courier, producer)).let(CivsRules::validate)
        val target = (world.getBlockAt(6, 64, 0).state as Barrel).inventory
        repeat(target.size) { target.setItem(it, ItemStack(Material.STONE, 64)) }
        val custom = named(Material.COAL, 63)
        target.setItem(target.size - 1, custom)
        val cargo = CivCargo(CivsLogistics.shipmentId(city.id, courier.id, courier.completedCycles,
            quarry.id, warehouse.id, "COAL", producer.id), warehouse.id, quarry.id, warehouse.id,
            quarry.entrance, "COAL", 64, inventoryBacked = true, sourceWorkerId = producer.id)
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        storage.cargoRoom(city, warehouse.id, "COAL") shouldBe 0L
        shouldThrow<CivsRejection> { storage.prepareCargoPickup(city, cargo) }.reason shouldBe "stale-delivery"
        target.setItem(0, null)
        storage.cargoRoom(city, warehouse.id, "COAL") shouldBe 64L
        shouldThrow<CivsRejection> { storage.prepareCargoPickup(city, cargo) }.reason shouldBe "stale-delivery"
        CivsEquipment.plainCount(city.workers.last(), "COAL") shouldBe 64
        target.getItem(0) shouldBe null
        target.getItem(target.size - 1) shouldBe custom
    }

    test("resident transfers retain native metadata in both directions and replay each endpoint once") {
        val player = paper.addPlayer("EquipmentOwner")
        player.openInventory(paper.server.createInventory(player, 9))
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        val axe = named(Material.IRON_AXE, 1).apply { editMeta { meta ->
            (meta as Damageable).damage = 19
            meta.addEnchant(Enchantment.UNBREAKING, 2, true)
            meta.persistentDataContainer.set(NamespacedKey("test", "owner"), PersistentDataType.STRING, "resident")
        } }
        player.inventory.setItem(0, axe)
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, null, 0, ClickType.LEFT)
        val receipt = residentTransaction(id, city, prepared).validated()
        receipt.residentTransfer!!.changes.map { it.slot } shouldBe listOf(0)
        (prepared.worker.items!![4]!!.data != null) shouldBe true
        player.inventory.getItem(0) shouldBe axe
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe null
        prepared.worker.items!![4]!!.stack() shouldBe axe
        (paper.playerDataSaveCount(player) > 0) shouldBe true
        storage.unlock(id)
        city = receipt.after
        val takeId = UUID.randomUUID()
        storage.reserve(city, takeId, player)
        val taken = residentTransaction(takeId, city, storage.prepareResidentTransfer(city, worker.id, player, 4, null, ClickType.SHIFT_LEFT))
        storage.seal(taken, keepViewersOpen = true)
        storage.reconcile(taken) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(taken) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe axe
        taken.after.workers.single().items!![4] shouldBe null
    }

    test("cursor equipment stages to player storage then targets the exact resident slot through a replayable receipt") {
        val player = paper.addPlayer("CursorEquipmentOwner")
        player.openInventory(paper.server.createInventory(player, 9))
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        val pickaxe = named(Material.IRON_PICKAXE, 1)
        player.setItemOnCursor(pickaxe)
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, 4, CIVS_RESIDENT_CURSOR_SLOT, ClickType.LEFT)
        val receipt = residentTransaction(id, city, prepared).validated()
        prepared.worker.items!![4]!!.stack() shouldBe pickaxe
        player.itemOnCursor.type.isAir shouldBe true
        player.inventory.getItem(0) shouldBe pickaxe
        receipt.residentTransfer!!.changes.single().slot shouldBe 0
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe null
    }

    test("cursor right click moves one item into the chosen bag slot and retains its remainder") {
        val player = paper.addPlayer("CursorSupplyOwner")
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        player.setItemOnCursor(ItemStack(Material.STONE, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, 9, CIVS_RESIDENT_CURSOR_SLOT, ClickType.RIGHT)
        val receipt = residentTransaction(id, city, prepared).validated()
        prepared.worker.items!![9] shouldBe CivResidentItem("STONE", 1)
        prepared.worker.items!![6] shouldBe null
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe ItemStack(Material.STONE, 7)
    }

    test("rejected cursor equipment planning leaves the same item owned by the player") {
        val player = paper.addPlayer("RejectedCursorOwner")
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        val pickaxe = named(Material.IRON_PICKAXE, 1)
        player.setItemOnCursor(pickaxe)
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        shouldThrow<CivsRejection> {
            storage.prepareResidentTransfer(city, worker.id, player, 0, CIVS_RESIDENT_CURSOR_SLOT, ClickType.LEFT)
        }.reason shouldBe "resident-slot-occupied"
        player.inventory.getItem(0) shouldBe pickaxe
        player.itemOnCursor.type.isAir shouldBe true
        CivsEquipment.slots(worker).all { it == null } shouldBe true
    }

    test("a full player inventory prevents cursor staging without changing either endpoint") {
        val player = paper.addPlayer("FullCursorOwner")
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        repeat(36) { player.inventory.setItem(it, ItemStack(Material.STONE, 64)) }
        val pickaxe = ItemStack(Material.IRON_PICKAXE)
        player.setItemOnCursor(pickaxe)
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        shouldThrow<CivsRejection> {
            storage.prepareResidentTransfer(city, worker.id, player, 4, CIVS_RESIDENT_CURSOR_SLOT, ClickType.LEFT)
        }.reason shouldBe "storage-full"
        player.itemOnCursor shouldBe pickaxe
        player.inventory.getItem(0) shouldBe ItemStack(Material.STONE, 64)
    }

    test("mission cargo remains visible but only the resident's surplus can be taken") {
        val player = paper.addPlayer("CargoOwner")
        val shipment = CivCargo(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            CivPosition(0, 64, 0), "BREAD", 2, inventoryBacked = true)
        val worker = CivWorker(UUID.randomUUID(), "Courier", null, cargo = shipment,
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("BREAD", 3) else null })
        val (updated, changes) = CivsEquipment.transfer(worker, player, 6, null, ClickType.LEFT)
        changes.single().after shouldBe CivResidentItem("BREAD", 1)
        updated.items!![6] shouldBe CivResidentItem("BREAD", 2)
        shouldThrow<CivsRejection> { CivsEquipment.transfer(updated, player, 6, null, ClickType.RIGHT) }
            .reason shouldBe "resident-item-reserved"
    }

    test("corpse loot journals exact belongings and cannot repeat the same withdrawal") {
        val player = paper.addPlayer("CorpseLootOwner")
        val pickaxe = named(Material.IRON_PICKAXE, 1)
        val dead = CivWorker(UUID.randomUUID(), "Fallen", null, items = List(CivsEquipment.SLOTS) {
            if (it == 4) CivResidentItem.of(pickaxe) else null
        })
        val corpse = CivCorpse(dead, CivPosition(0, 64, 0), 1_000, 301_000, "Zombie", "ZOMBIE", "ENTITY_ATTACK")
        city = city.copy(owner = player.uniqueId, corpses = listOf(corpse))
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, dead.id, player, 4, null, ClickType.LEFT)
        val after = city.copy(corpses = listOf(corpse.copy(worker = prepared.worker)), revision = city.revision + 1)
        val receipt = CivInventoryTransaction(id, city.id, city.revision, after, prepared.plan.changes,
            residentTransfer = prepared.plan.residentTransfer).validated()
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe pickaxe
        after.corpses!!.single().worker.items!![4] shouldBe null
        after.workers shouldBe city.workers
        storage.unlock(id)
        val repeated = UUID.randomUUID()
        storage.reserve(after, repeated, player)
        shouldThrow<CivsRejection> { storage.prepareResidentTransfer(after, dead.id, player, 4, null, ClickType.LEFT) }
            .reason shouldBe "resident-item-missing"
        player.inventory.getItem(0) shouldBe pickaxe
        shouldThrow<CivsRejection> { storage.prepareResidentTransfer(after, dead.id, player, null, 0, ClickType.LEFT) }
            .reason shouldBe "not-authorized"
    }

    test("requested tool selects the cheapest worn plain tier and equips it through the existing receipt once") {
        val player = paper.addPlayer("ToolDonor")
        val farm = addLocalBuilding(BuildingType.FARM)
        val items = MutableList<CivResidentItem?>(33) { null }.also { it[4] = CivResidentItem("STICK", 1) }
        val worker = CivWorker(UUID.randomUUID(), "Farmer", farm.id, nextDeliveryAt = 1_000, items = items)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        player.inventory.setItem(0, ItemStack(Material.IRON_HOE))
        player.inventory.setItem(1, ItemStack(Material.WOODEN_HOE).apply { addUnsafeEnchantment(Enchantment.UNBREAKING, 1) })
        player.inventory.setItem(2, ItemStack(Material.WOODEN_HOE))
        val chosen = named(Material.WOODEN_HOE, 1).apply { editMeta { (it as Damageable).damage = 21 } }
        player.inventory.setItem(3, chosen)
        player.inventory.setItem(4, ItemStack(Material.GOLDEN_HOE))
        player.inventory.setItem(5, ItemStack(Material.COPPER_PICKAXE))
        CivsEquipment.cheapestTool(player, Material.IRON_PICKAXE) shouldBe 5
        player.inventory.setItem(5, null)
        CivsEquipment.requestedTool(city, worker) shouldBe Material.IRON_HOE
        CivsEquipment.cheapestTool(player, Material.IRON_HOE) shouldBe 3
        CivsEquipment.cheapestTool(player, Material.IRON_PICKAXE) shouldBe null
        CivsEquipment.canEquipRequestedTool(worker) shouldBe true
        val full = worker.copy(items = items.mapIndexed { i, item -> if (i >= 6) CivResidentItem("STONE", 64) else item })
        CivsEquipment.canEquipRequestedTool(full) shouldBe false

        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, null, null, ClickType.RIGHT, Material.IRON_HOE)
        val receipt = residentTransaction(id, city, prepared).validated()
        player.inventory.getItem(3) shouldBe chosen
        prepared.worker.items!![4]!!.stack() shouldBe chosen
        prepared.worker.items!![6] shouldBe CivResidentItem("STICK", 1)
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(3) shouldBe null
        player.inventory.getItem(0)!!.type shouldBe Material.IRON_HOE
        player.inventory.getItem(1)!!.enchantments[Enchantment.UNBREAKING] shouldBe 1
        player.inventory.getItem(2)!!.type shouldBe Material.WOODEN_HOE
        CivsEquipment.requestedTool(receipt.after, prepared.worker) shouldBe null
    }

    test("requested supplies move a bounded batch from multiple player stacks and replay only once") {
        val player = paper.addPlayer("SupplyDonor")
        val worker = CivWorker(UUID.randomUUID(), "Miner", null, items = MutableList<CivResidentItem?>(33) { null }.also {
            it[4] = CivResidentItem("IRON_PICKAXE", 1)
            it[6] = CivResidentItem("COBBLESTONE", 12)
        })
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        player.inventory.setItem(0, ItemStack(Material.COBBLESTONE, 24))
        player.inventory.setItem(1, named(Material.COBBLESTONE, 64))
        player.inventory.setItem(2, ItemStack(Material.COBBLESTONE, 40))
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, null, null, ClickType.LEFT,
            requestedSupply = Material.COBBLESTONE to 52L)
        val receipt = residentTransaction(id, city, prepared).validated()
        CivsEquipment.plainCount(prepared.worker, "COBBLESTONE") shouldBe 64
        player.inventory.getItem(0)!!.amount shouldBe 24 // Planning is not a native debit.
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe null
        player.inventory.getItem(1)!!.amount shouldBe 64 // Named supplies are never silently consumed.
        player.inventory.getItem(2)!!.amount shouldBe 12
        prepared.worker.items!![4]!!.material shouldBe "IRON_PICKAXE"
    }

    test("requested supply accepts a partial donation and respects the resident bag capacity") {
        val player = paper.addPlayer("PartialSupplyDonor")
        val worker = CivWorker(UUID.randomUUID(), "Farmer", null, items = MutableList<CivResidentItem?>(33) { index ->
            if (index >= 6) CivResidentItem("STONE", 64) else null
        }.also { it[6] = CivResidentItem("WHEAT_SEEDS", 61) })
        player.inventory.setItem(0, ItemStack(Material.WHEAT_SEEDS, 8))
        val (filled, debit) = CivsEquipment.giveRequestedSupply(worker, player, Material.WHEAT_SEEDS, 64)
        CivsEquipment.plainCount(filled, "WHEAT_SEEDS") shouldBe 64
        debit.single().after!!.amount shouldBe 5
        val emptyBag = worker.copy(items = List(33) { null })
        val (partial, partialDebit) = CivsEquipment.giveRequestedSupply(emptyBag, player, Material.WHEAT_SEEDS, 64)
        CivsEquipment.plainCount(partial, "WHEAT_SEEDS") shouldBe 8
        partialDebit.single().after shouldBe null
        player.inventory.getItem(0)!!.amount shouldBe 8
    }

    test("resident receipts wait for rejoin and retry an already applied player change after a failed save") {
        val player = paper.addPlayer("ReturningEquipmentOwner")
        player.openInventory(paper.server.createInventory(player, 9))
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        player.inventory.setItem(0, ItemStack(Material.BREAD, 8))
        storage.close()
        var saves = 0
        val persistence = PaperPlayerDataPersistence { if (++saves == 1) throw java.io.IOException("player save unavailable") }
        storage = CivsStorage(plugin, persistence) { emptyArray() }
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, null, 0, ClickType.RIGHT)
        val receipt = residentTransaction(id, city, prepared)
        storage.seal(receipt, keepViewersOpen = true)
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        player.inventory.getItem(0)!!.amount shouldBe 7
        storage.close()
        player.disconnect()
        storage = CivsStorage(plugin, persistence) { emptyArray() }
        storage.lock(receipt)
        storage.waitingForPlayer(receipt) shouldBe true
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        player.reconnect()
        storage.waitingForPlayer(receipt) shouldBe false
        paper.callEvent(PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe true
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0)!!.amount shouldBe 7
        CivsEquipment.plainCount(receipt.after.workers.single(), "BREAD") shouldBe 1
        storage.unlock(id)
        paper.callEvent(PlayerCommandPreprocessEvent(player, "/sellall")).isCancelled shouldBe false
    }

    test("resident transfer rejects metadata drift before changing or saving any player slot") {
        val player = paper.addPlayer("DriftedEquipmentOwner")
        player.openInventory(paper.server.createInventory(player, 9))
        val worker = CivWorker(UUID.randomUUID(), "Resident", null)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        player.inventory.setItem(0, ItemStack(Material.IRON_AXE))
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val receipt = residentTransaction(id, city, storage.prepareResidentTransfer(city, worker.id, player, null, 0, ClickType.LEFT))
        storage.seal(receipt, keepViewersOpen = true)
        val changed = named(Material.IRON_AXE, 1)
        player.inventory.setItem(0, changed)
        val saves = paper.playerDataSaveCount(player)
        (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "player-slot-drift"
        player.inventory.getItem(0) shouldBe changed
        paper.playerDataSaveCount(player) shouldBe saves
    }

    test("Auto upkeep is fail closed without native authority and rechecks the same authority on every replay") {
        val (before, receipt) = upkeepStorageReceipt(city)
        val floor = receipt.worldChanges!!.single().position
        world.getBlockAt(floor.x, floor.y, floor.z).type = Material.DIRT
        storage.replaceCities(listOf(before))
        storage.reserve(before, receipt.id)
        storage.seal(receipt)
        (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "upkeep-authority-unavailable"
        world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.DIRT
        var checks = 0
        storage.validateUpkeepNative = { checks++; null }
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        checks shouldBe 2
        world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.COBBLESTONE
        receipt.after.stock shouldBe before.stock.copy(stone = before.stock.stone - 1)
        receipt.after.workers.single().copy(completedCycles = 0, nextDeliveryAt = 1) shouldBe before.workers.single()
    }

    test("physical Auto upkeep debits only its own hut once and rechecks hut Lands before any native write") {
        val (legacy, old) = upkeepStorageReceipt(city)
        val hut = legacy.building(old.upkeep!!.work.buildingId)!!.copy(control = CivPosition(10, 64, 0))
        city = legacy.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            buildings = legacy.buildings.map { if (it.id == hut.id) hut else it })
        world.getBlockAt(10, 64, 0).type = Material.BARREL
        val own = (world.getBlockAt(10, 64, 0).state as Barrel).inventory
        own.setItem(0, ItemStack(Material.COBBLESTONE, 2))
        barrel.setItem(0, ItemStack(Material.COBBLESTONE, 64)) // Another building is never charged.
        val work = old.upkeep.work.copy(inputs = mapOf("COBBLESTONE" to 1L))
        val floor = work.upkeep!!.floor
        world.getBlockAt(floor.x, floor.y, floor.z).type = Material.DIRT
        val id = UUID.randomUUID()
        storage.replaceCities(listOf(city)); storage.reserve(city, id)
        val plan = storage.prepareWork(city, hut.id, work.storageInputs(city.workers.single()), emptyMap(), work.changes)
        val after = CivsRoadMaintenance.complete(city, work, 1_000, nativePaid = true)
        val tx = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes, worldChanges = work.changes,
            upkeep = CivRoadUpkeepReceipt(city.workers.single(), city.stock, work, 1_000))
        tx.validated()
        storage.seal(tx)
        storage.validateUpkeepNative = { null }
        storage.foodCanModify = { _, point -> point != hut.control }
        (storage.reconcile(tx) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "lands-denied"
        own.getItem(0)!!.amount shouldBe 2
        world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.DIRT
        storage.foodCanModify = { _, _ -> true }
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        own.getItem(0)!!.amount shouldBe 1
        barrel.getItem(0)!!.amount shouldBe 64
        world.getBlockAt(floor.x, floor.y, floor.z).type shouldBe Material.COBBLESTONE
        storage.foodCanModify = { _, _ -> false }
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        own.getItem(0)!!.amount shouldBe 1
        tx.after.stock shouldBe Stock()
        storage.unlock(id)
    }

    test("Auto field receipt needs native authority on every replay and preserves naturally grown crops") {
        val (fixture, work) = visualReceiptFixture("farmer")
        val before = fixture.copy(worldId = world.uid)
        val receipt = autonomousVisualTransaction(before, work)
        val point = work.changes.single().position
        world.loadChunk(point.x shr 4, point.z shr 4)
        val crop = world.getBlockAt(point.x, point.y, point.z)
        crop.type = Material.AIR
        storage.replaceCities(listOf(before))
        storage.reserve(before, receipt.id)
        storage.seal(receipt)
        (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "autonomous-work-authority-unavailable"
        crop.type shouldBe Material.AIR
        var checks = 0
        storage.validateAutonomousVisualNative = { checks++; null }
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        crop.blockData.asString shouldBe "minecraft:wheat[age=0]"
        crop.blockData = org.bukkit.Bukkit.createBlockData("minecraft:wheat[age=2]")
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        crop.blockData.asString shouldBe "minecraft:wheat[age=2]"
        checks shouldBe 2
        receipt.after.workers shouldBe before.workers
        receipt.after.stock shouldBe before.stock
        crop.type = Material.DIAMOND_BLOCK
        (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "world-cell-drift"
        crop.type shouldBe Material.DIAMOND_BLOCK
    }

    test("Auto visual replay retries a temporarily unavailable native authority without writing") {
        val (fixture, work) = visualReceiptFixture("miner")
        val before = fixture.copy(worldId = world.uid)
        val receipt = autonomousVisualTransaction(before, work)
        val cell = work.changes.single()
        world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
        val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
        block.blockData = org.bukkit.Bukkit.createBlockData(cell.before)
        storage.replaceCities(listOf(before))
        storage.reserve(before, receipt.id)
        storage.seal(receipt)
        storage.validateAutonomousVisualNative = { CivInventoryApplyResult.Retry(mapOf("reason" to "worksite-unloaded")) }
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        block.blockData.asString shouldBe cell.before
        storage.validateAutonomousVisualNative = { null }
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        block.blockData.asString shouldBe cell.after
        receipt.after.stock shouldBe before.stock
        receipt.after.workers shouldBe before.workers
    }

    test("upkeep recovery preserves neighbour derived stair shape but rejects a changed facing") {
        val after = "minecraft:cobblestone_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
        val (before, receipt) = upkeepStorageReceipt(city, after)
        val floor = receipt.worldChanges!!.single().position
        val corner = after.replace("shape=straight", "shape=inner_left")
        world.getBlockAt(floor.x, floor.y, floor.z).blockData = org.bukkit.Bukkit.createBlockData(corner)
        storage.replaceCities(listOf(before))
        storage.reserve(before, receipt.id)
        storage.seal(receipt)
        storage.validateUpkeepNative = { null }
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        world.getBlockAt(floor.x, floor.y, floor.z).blockData.asString shouldBe corner
        world.getBlockAt(floor.x, floor.y, floor.z).blockData = org.bukkit.Bukkit.createBlockData(after.replace("facing=east", "facing=west"))
        (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "world-cell-drift"
    }

    test("physical work exchanges inputs outputs and exact world cells through one replayable receipt") {
        city = city.copy(inventory = city.inventory!!.copy(mode = CivStorageMode.PHYSICAL))
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 2))
        val block = world.getBlockAt(2, 64, 1).also { it.type = Material.STONE }
        val position = CivPosition(2, 64, 1)
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareWork(city, city.buildings.single().id, mapOf("OAK_LOG" to 2L),
            mapOf("OAK_PLANKS" to 8L, "STICK" to 4L), listOf(CivPlanBlock(position, block.blockData.asString, "minecraft:air")))
        val receipt = transaction(id, city, plan)
        storage.isWorldLocked(world.uid, position) shouldBe false
        storage.seal(receipt, keepViewersOpen = true)
        storage.isWorldLocked(world.uid, position) shouldBe true
        storage.isWorldLocked(world.uid, position.copy(x = 3)) shouldBe false
        block.type shouldBe Material.STONE
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        block.type shouldBe Material.AIR
        storage.buildingAvailable(city, city.buildings.single().id) shouldBe mapOf("OAK_PLANKS" to 8L, "STICK" to 4L)
        storage.isWorldLocked(world.uid, position) shouldBe true
        storage.unlock(id)
        storage.isWorldLocked(world.uid, position) shouldBe false
    }

    test("world drift prevents the whole work receipt before input debit or output credit") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 2))
        val block = world.getBlockAt(2, 64, 1).also { it.type = Material.STONE }
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val receipt = transaction(id, city, storage.prepareWork(city, city.buildings.single().id,
            mapOf("OAK_LOG" to 2L), mapOf("OAK_PLANKS" to 8L),
            listOf(CivPlanBlock(CivPosition(2, 64, 1), block.blockData.asString, "minecraft:air"))))
        storage.seal(receipt, keepViewersOpen = true)
        block.type = Material.DIAMOND_BLOCK
        (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "world-cell-drift"
        barrel.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 2)
        block.type shouldBe Material.DIAMOND_BLOCK
    }

    test("world receipt bootstrap keeps only its exact cells locked while their chunk is absent") {
        val block = world.getBlockAt(2, 64, 1).also { it.type = Material.STONE }
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val receipt = transaction(id, city, storage.prepareWork(city, city.buildings.single().id, emptyMap(), emptyMap(),
            listOf(CivPlanBlock(CivPosition(2, 64, 1), block.blockData.asString, "minecraft:air"))))
        storage.seal(receipt, keepViewersOpen = true)
        storage.close()
        world.unloadChunk(0, 0)
        storage = CivsStorage(plugin) { emptyArray() }
        storage.beginReceiptBootstrap()
        storage.completeReceiptBootstrap(listOf(receipt))
        storage.isWorldLocked(world.uid, CivPosition(2, 64, 1)) shouldBe true
        world.isChunkLoaded(0, 0) shouldBe false
        (storage.reconcile(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        world.isChunkLoaded(0, 0) shouldBe false
        world.loadChunk(0, 0)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        world.getBlockAt(2, 64, 1).type shouldBe Material.AIR
    }

    test("hopper checks skip idle holders but keep bootstrap and right-half receipt locks without snapshots") {
        val idle = mockk<Inventory>()
        fun move(source: Inventory, destination: Inventory) =
            InventoryMoveItemEvent(source, ItemStack(Material.STONE), destination, true).also(storage::onMove)
        move(idle, idle).isCancelled shouldBe false
        storage.beginReceiptBootstrap()
        move(idle, idle).isCancelled shouldBe true
        storage.completeReceiptBootstrap(emptyList())
        move(idle, idle).isCancelled shouldBe false

        val outside = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }.state as Chest
        val right = world.getBlockAt(registered.x, registered.y, registered.z).state as Chest
        val holder = mockk<DoubleChest> {
            every { getLeftSide(false) } returns outside
            every { getRightSide(false) } returns right
        }
        val doubleChest = mockk<Inventory> { every { getHolder(false) } returns holder }
        val free = mockk<Inventory> { every { getHolder(false) } returns null }
        val id = UUID.randomUUID()
        storage.lock(city, id)
        move(doubleChest, free).isCancelled shouldBe true
        move(free, doubleChest).isCancelled shouldBe true
        verify(exactly = 2) { doubleChest.getHolder(false) }
        storage.unlock(id)
        move(idle, idle).isCancelled shouldBe false
    }

    test("local physical stock includes warehouse holders but excludes other buildings and outside registration") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val interior = world.getBlockAt(7, 64, 0).also { it.type = Material.CHEST }
        val processorInterior = world.getBlockAt(2, 64, 0).also { it.type = Material.BARREL }
        indexedBlocks += listOf(interior, processorInterior)
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 2))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 64))
        cargo(interior).setItem(0, ItemStack(Material.OAK_LOG, 3))
        cargo(processorInterior).setItem(0, ItemStack(Material.OAK_LOG, 32))
        cargo(world.getBlockAt(6, 64, 0)).setItem(0, ItemStack(Material.OAK_LOG, 4))
        storage.buildingAvailable(city, city.buildings.first().id) shouldBe mapOf("OAK_LOG" to 2L)
        storage.buildingAvailable(city, warehouse.id) shouldBe mapOf("OAK_LOG" to 7L)
        storage.available(city) shouldBe mapOf("OAK_LOG" to 105L)
    }

    test("local construction debit cannot spend a warehouse or registered chest before delivery") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        chest.setItem(0, ItemStack(Material.STONE, 64))
        cargo(world.getBlockAt(6, 64, 0)).setItem(0, ItemStack(Material.STONE, 64))
        val first = UUID.randomUUID()
        storage.reserve(city, first)
        storage.prepareDebitBackground(city, mapOf("STONE" to 1L), city.buildings.first().id) shouldBe null
        storage.unlock(first)
        barrel.setItem(0, ItemStack(Material.STONE, 2))
        val second = UUID.randomUUID()
        storage.reserve(city, second)
        val plan = requireNotNull(storage.prepareDebitBackground(city, mapOf("STONE" to 1L), city.buildings.first().id))
        plan.changes.map { it.container }.toSet() shouldBe setOf(control)
        storage.apply(transaction(second, city, plan)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe ItemStack(Material.STONE, 1)
        storage.buildingAvailable(city, warehouse.id) shouldBe mapOf("STONE" to 64L)
        chest.getItem(0) shouldBe ItemStack(Material.STONE, 64)
    }

    test("manual construction supply fills its target despite enough materials elsewhere") {
        val player = paper.addPlayer("LocalSupplier")
        player.openInventory(paper.server.createInventory(player, 9))
        city = city.copy(owner = player.uniqueId)
        chest.setItem(0, ItemStack(Material.OAK_LOG, 64))
        player.inventory.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val supplied = storage.prepareConstructionSupply(city, city.buildings.single().id, mapOf("OAK_LOG" to 4L), player)
        supplied.result shouldBe CivsSupplyResult(4, 0, false)
        barrel.isEmpty shouldBe true
        storage.apply(transaction(id, city, supplied.plan)) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0) shouldBe null
        barrel.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 4)
        chest.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 64)
    }

    test("construction uses builder bags and physical hut storage without spending site hall or warehouse stock") {
        val hut = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE, 10)
        val target = addLocalBuilding(BuildingType.HOUSE, 14).copy(phase = BuildingPhase.CONSTRUCTING)
        val inner = world.getBlockAt(7, 64, 1).also { it.type = Material.CHEST; indexedBlocks += it }
        cargo(inner).setItem(0, ItemStack(Material.STONE, 3))
        cargo(world.getBlockAt(6, 64, 0)).setItem(0, ItemStack(Material.STONE, 1))
        cargo(world.getBlockAt(10, 64, 0)).setItem(0, ItemStack(Material.STONE, 64))
        cargo(world.getBlockAt(14, 64, 0)).setItem(0, ItemStack(Material.STONE, 64))
        barrel.setItem(0, ItemStack(Material.STONE, 64))
        chest.setItem(0, ItemStack(Material.STONE, 64))
        val builder = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1, items = List(CivsEquipment.SLOTS) {
            when (it) { 6 -> CivResidentItem("STONE", 2); 7 -> CivResidentItem.of(named(Material.STONE, 3)); else -> null }
        })
        val required = mapOf("STONE" to 5L)
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 1, 0, ConstructionPhase.READY_TO_PLACE,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(1, 16), materials = required,
            payment = CivConstructionPayment())
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
            workers = listOf(builder), construction = order))
        storage.constructionAvailable(city, target.id) shouldBe mapOf("STONE" to 6L)
        val remaining = CivsRules.constructionStorageInputs(city, target.id, required)
        remaining shouldBe mapOf("STONE" to 3L)
        shouldThrow<CivsRejection> { CivsRules.payBatch(city, city.owner, order.id, 0, required) }.reason shouldBe "materials-missing"
        val pickupId = UUID.randomUUID()
        storage.reserve(city, pickupId)
        val pickup = residentTransaction(pickupId, city, storage.prepareBuilderPickup(city, builder.id)).validated()
        pickup.changes.map { it.container }.toSet() shouldBe setOf(requireNotNull(hut.control), CivPosition(7, 64, 1))
        CivsEquipment.plainCount(pickup.after.workers.single(), "STONE") shouldBe 5
        storage.seal(pickup)
        storage.reconcile(pickup) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(pickup) shouldBe CivInventoryApplyResult.Applied
        storage.unlock(pickupId)
        city = pickup.after
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = requireNotNull(storage.prepareConstructionDebitBackground(city,
            CivsRules.constructionStorageInputs(city, target.id, required), target.id))
        val paid = CivsRules.payBatch(city, city.owner, order.id, 0, required).copy(inventory = plan.inventoryAfter)
        val receipt = CivInventoryTransaction(id, city.id, city.revision, paid, plan.changes).validated()
        plan.changes shouldBe emptyList()
        CivsEquipment.plainCount(paid.workers.single(), "STONE") shouldBe 0
        paid.workers.single().items!![7] shouldBe builder.items!![7]
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)!!.amount shouldBe 64
        chest.getItem(0)!!.amount shouldBe 64
        storage.buildingAvailable(city, warehouse.id)["STONE"] shouldBe 64L
        storage.buildingAvailable(city, target.id)["STONE"] shouldBe 64L
        shouldThrow<CivsRejection> { CivsRules.payBatch(paid, paid.owner, order.id, 0, required) }.reason shouldBe "stale-construction"
    }

    test("builder pickup carries multiple stacks for future rows and never borrows another building's stock") {
        val hut = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
        val target = addLocalBuilding(BuildingType.HOUSE, 10).copy(phase = BuildingPhase.CONSTRUCTING)
        val native = cargo(world.getBlockAt(6, 64, 0))
        repeat(4) { native.setItem(it, ItemStack(Material.STONE, 64)) }
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 64))
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("STONE", 2) else null })
        val required = mapOf("STONE" to 400L, "OAK_LOG" to 64L)
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 30, 0, ConstructionPhase.READY_TO_PLACE,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(30, 16), materials = required,
            payment = CivConstructionPayment())
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
            workers = listOf(worker), construction = order))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareBuilderPickup(city, worker.id)
        CivsEquipment.plainCount(prepared.worker, "STONE") shouldBe 258
        CivsEquipment.plainCount(prepared.worker, "OAK_LOG") shouldBe 0
        val receipt = residentTransaction(id, city, prepared).validated()
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        native.isEmpty shouldBe true
        barrel.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 64)
    }

    test("a full builder bag returns spare blocks to make room for the next row") {
        val hut = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
        val target = addLocalBuilding(BuildingType.HOUSE, 10).copy(phase = BuildingPhase.CONSTRUCTING)
        val native = cargo(world.getBlockAt(6, 64, 0))
        native.setItem(0, ItemStack(Material.TORCH, 16))
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { if (it >= CivsEquipment.BAG) CivResidentItem("COBBLESTONE", 64) else null })
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 30, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(30, 16),
            materials = mapOf("COBBLESTONE" to 2000L, "TORCH" to 16L),
            payment = CivConstructionPayment(awaiting = mapOf("TORCH" to 16L)))
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
            workers = listOf(worker), construction = order))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareBuilderPickup(city, worker.id)
        CivsEquipment.plainCount(prepared.worker, "TORCH") shouldBe 16
        CivsEquipment.plainCount(prepared.worker, "COBBLESTONE") shouldBe 26 * 64
        val receipt = residentTransaction(id, city, prepared).validated()
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        native.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 64)
    }

    test("a full builder bag returns surplus of a required ingredient to carry the complete next row") {
        val hut = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
        val target = addLocalBuilding(BuildingType.HOUSE, 10).copy(phase = BuildingPhase.CONSTRUCTING)
        val native = cargo(world.getBlockAt(6, 64, 0))
        native.setItem(0, ItemStack(Material.OAK_SAPLING, 16))
        val required = CivsMaterials.requirements("minecraft:potted_oak_sapling").mapValues { (_, count) -> count * 16 }
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { if (it >= CivsEquipment.BAG) CivResidentItem("FLOWER_POT", 64) else null })
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 30, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(30, 16),
            materials = required + ("FLOWER_POT" to 2000L), payment = CivConstructionPayment(awaiting = required))
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
            workers = listOf(worker), construction = order))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareBuilderPickup(city, worker.id)
        CivsEquipment.plainCount(prepared.worker, "FLOWER_POT") shouldBe 26 * 64
        CivsEquipment.plainCount(prepared.worker, "OAK_SAPLING") shouldBe 16
        native.getItem(0) shouldBe ItemStack(Material.OAK_SAPLING, 16)
        val receipt = residentTransaction(id, city, prepared).validated()
        CivsRules.constructionStorageInputs(receipt.after, target.id, required) shouldBe emptyMap()
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        native.getItem(0) shouldBe ItemStack(Material.FLOWER_POT, 64)
    }

    test("builder priority exchange retains the exact next row requirement before returning other supplies") {
        val hut = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
        val target = addLocalBuilding(BuildingType.HOUSE, 10).copy(phase = BuildingPhase.CONSTRUCTING)
        val native = cargo(world.getBlockAt(6, 64, 0))
        native.setItem(0, ItemStack(Material.OAK_SAPLING, 16))
        val required = CivsMaterials.requirements("minecraft:potted_oak_sapling").mapValues { (_, count) -> count * 16 }
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { when {
                it == CivsEquipment.SLOTS - 1 -> CivResidentItem("FLOWER_POT", 16)
                it >= CivsEquipment.BAG -> CivResidentItem("COBBLESTONE", 64)
                else -> null
            } })
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 30, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(30, 16),
            materials = required + ("COBBLESTONE" to 2000L), payment = CivConstructionPayment(awaiting = required))
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
            workers = listOf(worker), construction = order))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareBuilderPickup(city, worker.id)
        prepared.worker.items!![CivsEquipment.SLOTS - 1] shouldBe CivResidentItem("FLOWER_POT", 16)
        CivsEquipment.plainCount(prepared.worker, "COBBLESTONE") shouldBe 25 * 64
        CivsEquipment.plainCount(prepared.worker, "OAK_SAPLING") shouldBe 16
        val receipt = residentTransaction(id, city, prepared).validated()
        CivsRules.constructionStorageInputs(receipt.after, target.id, required) shouldBe emptyMap()
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        native.getItem(0) shouldBe ItemStack(Material.COBBLESTONE, 64)
    }

    for ((material, block) in mapOf("REDSTONE" to "redstone_wire", "STRING" to "tripwire", "WHEAT_SEEDS" to "wheat"))
        test("builder priority exchange accepts plain construction ingredient $material and preserves personal items") {
            CivsMaterials.requirements("minecraft:$block") shouldBe mapOf(material to 1L)
            Material.valueOf(material).isBlock shouldBe false
            val hut = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
            val target = addLocalBuilding(BuildingType.HOUSE, 10).copy(phase = BuildingPhase.CONSTRUCTING)
            val native = cargo(world.getBlockAt(6, 64, 0))
            native.setItem(0, ItemStack(Material.TORCH, 16))
            val custom = CivResidentItem.of(named(Material.valueOf(material), 1))
            val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
                items = List(CivsEquipment.SLOTS) { when (it) {
                    6 -> CivResidentItem("BREAD", 4)
                    7 -> CivResidentItem("IRON_PICKAXE", 1)
                    8 -> custom
                    in 9 until CivsEquipment.SLOTS -> CivResidentItem(material, 64)
                    else -> null
                } })
            val required = mapOf("TORCH" to 16L)
            val order = CivConstruction(UUID.randomUUID(), target.id, 1, 30, 0, ConstructionPhase.WAITING_MATERIALS,
                Stock(), plannedBuilding = target, batches = CivConstructionBatches(30, 16),
                materials = required + (material to 2000L), payment = CivConstructionPayment(awaiting = required))
            city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
                workers = listOf(worker), construction = order))
            val id = UUID.randomUUID()
            storage.reserve(city, id)
            val prepared = storage.prepareBuilderPickup(city, worker.id)
            prepared.worker.items!!.subList(6, 9) shouldBe worker.items!!.subList(6, 9)
            CivsEquipment.plainCount(prepared.worker, material) shouldBe 23 * 64
            CivsEquipment.plainCount(prepared.worker, "TORCH") shouldBe 16
            val receipt = residentTransaction(id, city, prepared).validated()
            storage.seal(receipt)
            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
            native.getItem(0) shouldBe ItemStack(Material.valueOf(material), 64)
        }

    test("resident food pickup debits only the town hall and carries real bread in the same receipt") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val worker = CivWorker(UUID.randomUUID(), "Hungry", null, hunger = 8)
        city = city.copy(workers = listOf(worker))
        barrel.setItem(0, ItemStack(Material.BREAD, 6))
        cargo(world.getBlockAt(6, 64, 0)).setItem(0, ItemStack(Material.BREAD, 64))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareResidentPickup(city, worker.id, city.buildings.first().id, mapOf("BREAD" to 4L))
        CivsEquipment.plainCount(prepared.worker, "BREAD") shouldBe 4
        barrel.getItem(0) shouldBe ItemStack(Material.BREAD, 6)
        val receipt = residentTransaction(id, city, prepared).validated()
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe ItemStack(Material.BREAD, 2)
        storage.buildingAvailable(city, warehouse.id)["BREAD"] shouldBe 64L
        CivsEquipment.plainCount(receipt.after.workers.single(), "BREAD") shouldBe 4
    }

    test("builder bulk deposits conserve multiple materials metadata and partial bag capacity across replay") {
        val player = paper.addPlayer("BuilderSupplier")
        player.openInventory(paper.server.createInventory(player, 9))
        val hut = addLocalBuilding(BuildingType.BUILDER_HUT)
        val items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { if (it >= CivsEquipment.BAG) CivResidentItem("DIRT", 64) else null }
        items[6] = CivResidentItem("STONE", 63); items[7] = null
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1, items = items)
        city = city.copy(owner = player.uniqueId, workers = listOf(worker))
        player.inventory.setItem(0, ItemStack(Material.STONE, 4))
        player.inventory.setItem(1, ItemStack(Material.OAK_LOG, 64))
        val custom = named(Material.OAK_LOG, 64)
        player.inventory.setItem(2, custom)
        player.inventory.setItemInOffHand(ItemStack(Material.STONE, 10))
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val prepared = storage.prepareResidentTransfer(city, worker.id, player, null, null, ClickType.LEFT,
            requestedSupplies = mapOf("STONE" to 1L, "OAK_LOG" to 70L))
        val receipt = residentTransaction(id, city, prepared).validated()
        CivsEquipment.plainCount(prepared.worker, "STONE") shouldBe 64
        CivsEquipment.plainCount(prepared.worker, "OAK_LOG") shouldBe 64
        receipt.residentTransfer!!.changes.map { it.slot } shouldBe listOf(0, 1)
        storage.seal(receipt, keepViewersOpen = true)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0)!!.amount shouldBe 3
        player.inventory.getItem(1) shouldBe null
        player.inventory.getItem(2) shouldBe custom
        player.inventory.itemInOffHand.amount shouldBe 10
        val (issued, moved) = CivsEquipment.grantSupplies(prepared.worker, mapOf("OAK_LOG" to 10L, "STONE" to 10L))
        issued shouldBe prepared.worker
        moved shouldBe emptyMap()
    }

    test("explicit virtual construction spends only hut stocks and real builder bags across multiple huts") {
        val first = addLocalBuilding(BuildingType.BUILDER_HUT, 6)
        val second = addLocalBuilding(BuildingType.BUILDER_HUT, 10)
        val target = addLocalBuilding(BuildingType.HOUSE, 14)
        val worker = CivWorker(UUID.randomUUID(), "Builder", first.id, nextDeliveryAt = 1, items = List(CivsEquipment.SLOTS) {
            if (it == 6) CivResidentItem("STONE", 2) else null
        })
        city = city.copy(workers = listOf(worker), inventory = CivInventory(CivStorageMode.VIRTUAL,
            mapOf("STONE" to 64L), buildingMaterials = mapOf(city.buildings.first().id.toString() to mapOf("STONE" to 64L),
                target.id.toString() to mapOf("STONE" to 64L), first.id.toString() to mapOf("STONE" to 1L),
                second.id.toString() to mapOf("STONE" to 2L))))
        storage.constructionAvailable(city, target.id) shouldBe mapOf("STONE" to 5L)
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = requireNotNull(storage.prepareConstructionDebitBackground(city,
            CivsRules.constructionStorageInputs(city, target.id, mapOf("STONE" to 5L)), target.id))
        plan.changes shouldBe emptyList()
        plan.inventoryAfter.buildingStock(first.id) shouldBe emptyMap()
        plan.inventoryAfter.buildingStock(second.id) shouldBe emptyMap()
        plan.inventoryAfter.buildingStock(target.id) shouldBe mapOf("STONE" to 64L)
        plan.inventoryAfter.buildingStock(city.buildings.first().id) shouldBe mapOf("STONE" to 64L)
        plan.inventoryAfter.materials shouldBe mapOf("STONE" to 64L)
        storage.prepareConstructionDebitBackground(city, mapOf("STONE" to 4L), target.id) shouldBe null
    }

    test("an old unpaid water only row completes without consuming or refunding existing water buckets") {
        val target = addLocalBuilding(BuildingType.BUILDER_HUT).copy(phase = BuildingPhase.CONSTRUCTING)
        val worker = CivWorker(UUID.randomUUID(), "Builder", target.id, nextDeliveryAt = 1, items = List(CivsEquipment.SLOTS) {
            if (it == 6) CivResidentItem("WATER_BUCKET", 1) else null
        })
        val old = mapOf("WATER_BUCKET" to 1L)
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 1, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(1, 16), materials = old,
            payment = CivConstructionPayment(awaiting = old))
        city = CivsRules.validate(city.copy(buildings = city.buildings.map { if (it.id == target.id) target else it },
            workers = listOf(worker), construction = order))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = requireNotNull(storage.prepareConstructionDebitBackground(city, emptyMap(), target.id))
        val paid = CivsRules.payBatch(city, city.owner, order.id, 0, emptyMap()).copy(inventory = plan.inventoryAfter)
        val receipt = CivInventoryTransaction(id, city.id, city.revision, paid, plan.changes).validated()
        receipt.changes shouldBe emptyList()
        paid.construction!!.materials shouldBe emptyMap()
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        val completed = CivsRules.completeBatch(paid, paid.owner, order.id)
        completed.construction shouldBe null
        completed.workers.single().items shouldBe worker.items
        CivsRules.completeBatch(completed, completed.owner, order.id) shouldBe completed
    }

    test("physical cargo pickup and dropoff conserve exact local stock across receipt replays") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val warehouseControl = cargo(world.getBlockAt(6, 64, 0))
        val interior = world.getBlockAt(7, 64, 0).also { it.type = Material.CHEST }
        indexedBlocks += interior
        warehouseControl.setItem(0, ItemStack(Material.OAK_LOG, 2))
        cargo(interior).setItem(0, ItemStack(Material.OAK_LOG, 3))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 64))
        val shipment = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, city.buildings.first().id,
            warehouse.entrance, "OAK_LOG", 4)
        val pickupId = UUID.randomUUID()
        storage.reserve(city, pickupId)
        val picked = transaction(pickupId, city, storage.prepareCargoPickup(city, shipment))
        picked.changes.map { it.container }.toSet() shouldBe setOf(warehouse.control, CivPosition(7, 64, 0))
        warehouseControl.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 2)
        storage.seal(picked, keepViewersOpen = true)
        storage.apply(picked) shouldBe CivInventoryApplyResult.Applied
        storage.apply(picked) shouldBe CivInventoryApplyResult.Applied
        warehouseControl.isEmpty shouldBe true
        cargo(interior).getItem(0) shouldBe ItemStack(Material.OAK_LOG, 1)
        barrel.isEmpty shouldBe true
        chest.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 64)
        storage.unlock(pickupId)
        city = picked.after
        val dropId = UUID.randomUUID()
        storage.reserve(city, dropId)
        val dropped = transaction(dropId, city, storage.prepareCargoDropoff(city, shipment, shipment.targetBuildingId))
        dropped.changes.map { it.container }.toSet() shouldBe setOf(control)
        barrel.isEmpty shouldBe true
        storage.seal(dropped, keepViewersOpen = true)
        storage.apply(dropped) shouldBe CivInventoryApplyResult.Applied
        storage.apply(dropped) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 4)
        storage.available(city)["OAK_LOG"] shouldBe 69L
    }

    test("cargo cannot overflow its target into unrelated free storage") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val shipment = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, city.buildings.first().id,
            warehouse.entrance, "OAK_LOG", 4)
        repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        shouldThrow<CivsRejection> { storage.prepareCargoDropoff(city, shipment, shipment.targetBuildingId) }
            .reason shouldBe "storage-full"
        chest.isEmpty shouldBe true
        barrel.getItem(0) shouldBe ItemStack(Material.STONE, 64)
        barrel.setItem(0, null)
        val receipt = transaction(id, city, storage.prepareCargoDropoff(city, shipment, shipment.targetBuildingId))
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 4)
        chest.isEmpty shouldBe true
    }

    test("virtual cargo only debits and credits its endpoint maps without spending the shared reserve") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val target = city.buildings.first()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL,
            materials = mapOf("OAK_LOG" to 100L), buildingMaterials = mapOf(
                target.id.toString() to mapOf("OAK_LOG" to 2L), warehouse.id.toString() to mapOf("OAK_LOG" to 5L))))
        storage.buildingAvailable(city, target.id) shouldBe mapOf("OAK_LOG" to 2L)
        storage.buildingAvailable(city, warehouse.id) shouldBe mapOf("OAK_LOG" to 5L)
        val shipment = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, target.id, warehouse.entrance, "OAK_LOG", 4)
        val pickupId = UUID.randomUUID()
        storage.reserve(city, pickupId)
        val picked = transaction(pickupId, city, storage.prepareCargoPickup(city, shipment))
        picked.changes shouldBe emptyList()
        picked.after.inventory!!.buildingStock(warehouse.id) shouldBe mapOf("OAK_LOG" to 1L)
        picked.after.inventory!!.buildingStock(target.id) shouldBe mapOf("OAK_LOG" to 2L)
        picked.after.inventory!!.materials shouldBe mapOf("OAK_LOG" to 100L)
        storage.unlock(pickupId)
        city = picked.after
        val dropId = UUID.randomUUID()
        storage.reserve(city, dropId)
        val dropped = storage.prepareCargoDropoff(city, shipment, target.id)
        dropped.changes shouldBe emptyList()
        dropped.inventoryAfter.buildingStock(target.id) shouldBe mapOf("OAK_LOG" to 6L)
        dropped.inventoryAfter.buildingStock(warehouse.id) shouldBe mapOf("OAK_LOG" to 1L)
        dropped.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 100L)
    }

    test("production cannot borrow recipe ingredients from other local stores or registered containers") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val hall = city.buildings.first().copy(phase = BuildingPhase.ACTIVE)
        city = city.copy(buildings = listOf(hall, warehouse))
        barrel.setItem(0, ItemStack(Material.PAPER, 3))
        chest.setItem(0, ItemStack(Material.LEATHER, 1))
        cargo(world.getBlockAt(6, 64, 0)).setItem(0, ItemStack(Material.LEATHER, 1))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        shouldThrow<CivsRejection> { storage.prepareProduction(city, hall) }.reason shouldBe "materials-missing"
        barrel.getItem(0) shouldBe ItemStack(Material.PAPER, 3)
        chest.getItem(0) shouldBe ItemStack(Material.LEATHER, 1)
        storage.buildingAvailable(city, warehouse.id) shouldBe mapOf("LEATHER" to 1L)
    }

    test("virtual production spends only its local recipe and leaves reserves and other stores unchanged") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val hall = city.buildings.first().copy(phase = BuildingPhase.ACTIVE)
        val recipe = mapOf("PAPER" to 3L, "LEATHER" to 1L)
        city = city.copy(buildings = listOf(hall, warehouse), inventory = requireNotNull(city.inventory).copy(
            mode = CivStorageMode.VIRTUAL, materials = recipe, buildingMaterials = mapOf(warehouse.id.toString() to recipe)))
        val first = UUID.randomUUID()
        storage.reserve(city, first)
        shouldThrow<CivsRejection> { storage.prepareProduction(city, hall) }.reason shouldBe "materials-missing"
        storage.unlock(first)
        city = city.copy(inventory = requireNotNull(city.inventory).copy(buildingMaterials =
            mapOf(warehouse.id.toString() to recipe, hall.id.toString() to recipe)))
        world.unloadChunk(0, 0)
        val second = UUID.randomUUID()
        storage.reserve(city, second)
        val plan = storage.prepareProduction(city, hall)
        plan.changes shouldBe emptyList()
        plan.inventoryAfter.buildingStock(hall.id) shouldBe mapOf("BOOK" to 1L)
        plan.inventoryAfter.buildingStock(warehouse.id) shouldBe recipe
        plan.inventoryAfter.materials shouldBe recipe
        world.isChunkLoaded(0, 0) shouldBe false
    }

    for (active in listOf(false, true)) test("warehouse construction uses interior stock only after activation active=$active") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE).copy(phase = if (active) BuildingPhase.ACTIVE else BuildingPhase.SITE)
        city = city.copy(buildings = city.buildings.map { if (it.id == warehouse.id) warehouse else it })
        val interior = world.getBlockAt(7, 64, 0).also { it.type = Material.CHEST }
        indexedBlocks += interior
        cargo(interior).setItem(0, ItemStack(Material.STONE, 1))
        chest.setItem(0, ItemStack(Material.STONE, 64))
        storage.buildingAvailable(city, warehouse.id) shouldBe if (active) mapOf("STONE" to 1L) else emptyMap()
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareDebitBackground(city, mapOf("STONE" to 1L), warehouse.id)
        if (active) {
            requireNotNull(plan).changes.map { it.container }.toSet() shouldBe setOf(CivPosition(7, 64, 0))
            storage.apply(transaction(id, city, plan)) shouldBe CivInventoryApplyResult.Applied
            cargo(interior).getItem(0) shouldBe null
        } else {
            plan shouldBe null
            cargo(interior).getItem(0) shouldBe ItemStack(Material.STONE, 1)
        }
        chest.getItem(0) shouldBe ItemStack(Material.STONE, 64)
    }

    for (ownFull in listOf(false, true)) test("materialization uses the active warehouse and pending own control first ownFull=$ownFull") {
        val warehouse = addLocalBuilding(BuildingType.WAREHOUSE)
        val hall = city.buildings.first()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(buildingMaterials =
            mapOf(hall.id.toString() to mapOf("BREAD" to 2L))))
        if (ownFull) repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareMaterialization(city)
        plan.inventoryAfter.materials shouldBe emptyMap()
        plan.inventoryAfter.buildingStock(hall.id) shouldBe emptyMap()
        barrel.getItem(0)?.type shouldBe if (ownFull) Material.STONE else null
        storage.apply(transaction(id, city, plan)) shouldBe CivInventoryApplyResult.Applied
        storage.buildingAvailable(city, warehouse.id) shouldBe if (ownFull)
            mapOf("OAK_LOG" to 3L, "BREAD" to 2L) else mapOf("OAK_LOG" to 3L)
        if (!ownFull) barrel.getItem(0) shouldBe ItemStack(Material.BREAD, 2)
        chest.isEmpty shouldBe true
    }

    test("a full active warehouse retains shared reserves even when the hall and registered chest are empty") {
        addLocalBuilding(BuildingType.WAREHOUSE)
        val warehouse = cargo(world.getBlockAt(6, 64, 0))
        repeat(warehouse.size) { warehouse.setItem(it, ItemStack(Material.STONE, 64)) }
        storage.reserve(city, UUID.randomUUID())
        val plan = storage.prepareMaterialization(city)
        plan.inventoryAfter shouldBe city.inventory
        plan.changes shouldBe emptyList()
        barrel.isEmpty shouldBe true
        chest.isEmpty shouldBe true
    }

    for (type in listOf(BuildingType.FORESTER, BuildingType.SAWMILL)) test("$type output stays in its own full barrel") {
        val producer = addLocalBuilding(type)
        val output = cargo(world.getBlockAt(6, 64, 0))
        repeat(output.size) { output.setItem(it, ItemStack(Material.STONE, 64)) }
        if (type == BuildingType.SAWMILL) output.setItem(0, ItemStack(Material.OAK_LOG, 64))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        shouldThrow<CivsRejection> { storage.prepareProduction(city, producer) }.reason shouldBe "storage-full"
        chest.isEmpty shouldBe true
        barrel.isEmpty shouldBe true
        output.getItem(0) shouldBe ItemStack(if (type == BuildingType.SAWMILL) Material.OAK_LOG else Material.STONE, 64)
    }

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
        supplied.result shouldBe CivsSupplyResult(8, 3, false)
        player.inventory.getItem(0)!!.amount shouldBe 10
        barrel.getItem(0)!!.amount shouldBe 4
        val receipt = transaction(id, city, supplied.plan).validated()
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        player.inventory.getItem(0)!!.amount shouldBe 4
        player.inventory.getItem(1) shouldBe custom
        player.inventory.getItem(2) shouldBe null
        player.inventory.itemInOffHand.amount shouldBe 20
        barrel.getItem(0)!!.amount shouldBe 10
        chest.getItem(0)!!.amount shouldBe 2
        storage.available(city) shouldBe mapOf("BREAD" to 2L, "OAK_LOG" to 12L)
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

    // MockBukkit 4.116.3 does not implement Inventory.getHolder(false) for native container events.
    test("sealing a receipt keeps unrelated containers open and interactive").config(enabled = false) {
        val touched = paper.addPlayer("TouchedViewer")
        val unrelated = paper.addPlayer("UnrelatedViewer")
        val touchedView = requireNotNull(touched.openInventory(barrel))
        val unrelatedView = requireNotNull(unrelated.openInventory(chest))
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        chest.setItem(0, ItemStack(Material.STONE, 7))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        (touched.openInventory.topInventory === barrel) shouldBe true
        val receipt = transaction(id, city, storage.prepareDebit(city, mapOf("OAK_LOG" to 1L)))
        storage.seal(receipt)
        (touched.openInventory.topInventory === barrel) shouldBe false
        (unrelated.openInventory.topInventory === chest) shouldBe true
        val blocked = InventoryClickEvent(touchedView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        val allowed = InventoryClickEvent(unrelatedView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        storage.onClick(blocked); storage.onClick(allowed)
        blocked.isCancelled shouldBe true
        allowed.isCancelled shouldBe false
        storage.available(city)["STONE"] shouldBe 7L
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)!!.amount shouldBe 3
        chest.getItem(0)!!.amount shouldBe 7
    }

    // MockBukkit 4.116.3 does not implement Inventory.getHolder(false) for native container events.
    for (debit in listOf(true, false)) test("background debit=$debit updates an open viewer and fences only its receipt until unlock").config(enabled = false) {
        val viewer = paper.addPlayer("BackgroundViewer")
        val unrelated = paper.addPlayer("UnrelatedBackgroundViewer")
        val openView = requireNotNull(viewer.openInventory(barrel))
        val unrelatedView = requireNotNull(unrelated.openInventory(chest))
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        chest.setItem(0, ItemStack(Material.STONE, 7))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = if (debit) storage.prepareDebit(city, mapOf("OAK_LOG" to 1L))
            else storage.prepareCredit(city, "OAK_LOG", 1, city.buildings.single().id)
        val receipt = transaction(id, city, plan)
        storage.seal(receipt, keepViewersOpen = true)
        (viewer.openInventory.topInventory === barrel) shouldBe true
        (unrelated.openInventory.topInventory === chest) shouldBe true
        val pending = InventoryClickEvent(openView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        val allowed = InventoryClickEvent(unrelatedView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        val hopper = InventoryMoveItemEvent(barrel, ItemStack(Material.OAK_LOG), chest, true)
        storage.onClick(pending); storage.onClick(allowed); storage.onMove(hopper)
        pending.isCancelled shouldBe true
        allowed.isCancelled shouldBe false
        hopper.isCancelled shouldBe true
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        (viewer.openInventory.topInventory === barrel) shouldBe true
        viewer.openInventory.topInventory.getItem(0) shouldBe ItemStack(Material.OAK_LOG, if (debit) 3 else 5)
        unrelated.openInventory.topInventory.getItem(0) shouldBe ItemStack(Material.STONE, 7)
        val applied = InventoryClickEvent(openView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        storage.onClick(applied)
        applied.isCancelled shouldBe true
        storage.unlock(id)
        val unlocked = InventoryClickEvent(openView, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        val unlockedHopper = InventoryMoveItemEvent(barrel, ItemStack(Material.OAK_LOG), chest, true)
        storage.onClick(unlocked); storage.onMove(unlockedHopper)
        unlocked.isCancelled shouldBe false
        unlockedHopper.isCancelled shouldBe false
        (viewer.openInventory.topInventory === barrel) shouldBe true
    }

    test("book production uses the same input and output snapshots including newly freed slots") {
        city = city.copy(buildings = city.buildings.map { it.copy(phase = BuildingPhase.ACTIVE) })
        repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        repeat(chest.size) { chest.setItem(it, ItemStack(Material.STONE, 64)) }
        barrel.setItem(0, ItemStack(Material.PAPER, 3))
        barrel.setItem(1, ItemStack(Material.LEATHER, 1))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val receipt = transaction(id, city, storage.prepareProduction(city, city.buildings.single())).validated()
        receipt.changes.size shouldBe 2
        barrel.getItem(0)!!.type shouldBe Material.PAPER
        barrel.getItem(1)!!.type shouldBe Material.LEATHER
        storage.seal(receipt)
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.apply(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)!!.type shouldBe Material.BOOK
        barrel.getItem(0)!!.amount shouldBe 1
        barrel.getItem(1) shouldBe null
        chest.getItem(0) shouldBe ItemStack(Material.STONE, 64)
    }

    test("missing book inputs or output capacity cannot consume any physical items") {
        city = city.copy(buildings = city.buildings.map { it.copy(phase = BuildingPhase.ACTIVE) })
        repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        repeat(chest.size) { chest.setItem(it, ItemStack(Material.STONE, 64)) }
        barrel.setItem(0, ItemStack(Material.PAPER, 64))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        shouldThrow<CivsRejection> { storage.prepareProduction(city, city.buildings.single()) }.reason shouldBe "materials-missing"
        barrel.setItem(1, ItemStack(Material.LEATHER, 64))
        shouldThrow<CivsRejection> { storage.prepareProduction(city, city.buildings.single()) }.reason shouldBe "storage-full"
        barrel.getItem(0)!!.amount shouldBe 64
        barrel.getItem(1)!!.amount shouldBe 64
    }

    test("virtual book production conserves its recipe in one plan") {
        val hall = city.buildings.single().copy(phase = BuildingPhase.ACTIVE)
        city = city.copy(buildings = listOf(hall), inventory = requireNotNull(city.inventory).copy(
            mode = CivStorageMode.VIRTUAL, materials = emptyMap(),
            buildingMaterials = mapOf(hall.id.toString() to mapOf("PAPER" to 3L, "LEATHER" to 1L))))
        storage.reserve(city, UUID.randomUUID())
        val plan = storage.prepareProduction(city, hall)
        plan.changes shouldBe emptyList()
        plan.inventoryAfter.buildingMaterials shouldBe mapOf(hall.id.toString() to mapOf("BOOK" to 1L))
        city.inventory!!.buildingMaterials shouldBe mapOf(hall.id.toString() to mapOf("PAPER" to 3L, "LEATHER" to 1L))
    }

    // MockBukkit 4.116.3 does not implement Inventory.getHolder(false) for native container events.
    test("locking closes viewers and blocks clicks and hopper moves until explicit unlock").config(enabled = false) {
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
        plan.inventoryAfter.buildingStock(building.id) shouldBe mapOf("BREAD" to 5L)
        plan.changes.size shouldBe 1
        barrel.getItem(0)?.amount shouldBe 63
        chest.getItem(0)?.amount shouldBe 60
        val receipt = transaction(id, city, plan).validated()
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)?.amount shouldBe 64
        chest.getItem(0)?.amount shouldBe 60
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
        val hall = city.buildings.single().copy(anchor = CivPosition(-3, 64, 0), entrance = CivPosition(-3, 64, 3), control = null,
            bounds = CivBox(CivPosition(-4, 64, 0), CivPosition(-2, 65, 2)))
        val warehouse = city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE,
            bounds = CivBox(CivPosition(2, 64, 0), CivPosition(11, 65, 3)))
        city = city.copy(buildings = listOf(hall, warehouse),
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
        val hall = city.buildings.single().copy(anchor = CivPosition(-3, 64, 0), entrance = CivPosition(-3, 64, 3), control = null,
            bounds = CivBox(CivPosition(-4, 64, 0), CivPosition(-2, 65, 2)))
        val warehouse = city.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE,
            bounds = CivBox(CivPosition(2, 64, 0), CivPosition(15, 65, 9)))
        city = city.copy(buildings = listOf(hall, warehouse),
            inventory = requireNotNull(city.inventory).copy(materials = mapOf("OAK_LOG" to 3L, "BREAD" to 5L, "COBBLESTONE" to 7L),
                buildingMaterials = mapOf(warehouse.id.toString() to mapOf("WHEAT" to 2L))))
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
            next.inventoryAfter.buildingStock(warehouse.id) shouldBe emptyMap()
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

    // Opening the player's inventory during a claim reaches MockBukkit's unsupported Inventory.getHolder(false).
    test("place and break update cached containers without scanning and new sources wait for the next claim").config(enabled = false) {
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

private fun upkeepStorageReceipt(base: Settlement, surface: String = "minecraft:cobblestone"): Pair<Settlement, CivInventoryTransaction> {
    val hut = CivBuilding(UUID.randomUUID(), BuildingType.BUILDER_HUT, 1, CivPosition(10, 64, 0), 0, "builder1",
        CivBox(CivPosition(9, 64, -1), CivPosition(11, 68, 1)), CivPosition(10, 64, -2), BuildingPhase.ACTIVE)
    val floor = CivPosition(6, 63, 0)
    val road = CivBuilding(UUID.randomUUID(), BuildingType.ROAD, 1, floor, 0, "civs/infrastructure/test-road",
        CivBox(floor, floor.copy(y = 65)), floor.copy(y = 64), BuildingPhase.ACTIVE,
        transit = listOf(floor.copy(y = 64)))
    val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1)
    val city = CivsRules.validate(base.copy(inventory = null, autonomy = CivAutonomy(), stock = Stock(96, 96, 24),
        buildings = listOf(base.buildings.first().copy(phase = BuildingPhase.ACTIVE), hut, road), workers = listOf(worker)))
    val work = CivWorldWork(worker.id, worker.completedCycles, hut.id, floor.copy(x = 5, y = 64),
        listOf(CivPlanBlock(floor, "minecraft:dirt", surface)), status = "repairing-road", focus = floor,
        upkeep = CivRoadUpkeep(city.id, city.worldId, city.owner, road.id, road, floor, surface, null, emptyList()))
    val after = CivsRoadMaintenance.complete(city, work, 1_000)
    return city to CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
        worldChanges = work.changes, upkeep = CivRoadUpkeepReceipt(worker, city.stock, work, 1_000)).validated()
}

private fun transaction(id: UUID, city: Settlement, plan: CivStoragePlan) = CivInventoryTransaction(
    id, city.id, city.revision, city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes, plan.playerDebit,
    plan.residentTransfer, plan.worldChanges,
)

private fun residentTransaction(id: UUID, city: Settlement, prepared: CivResidentTransferPlan) = CivInventoryTransaction(
    id, city.id, city.revision, city.copy(workers = city.workers.map { if (it.id == prepared.worker.id) prepared.worker else it },
        inventory = prepared.plan.inventoryAfter, revision = city.revision + 1), prepared.plan.changes,
    residentTransfer = prepared.plan.residentTransfer,
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
