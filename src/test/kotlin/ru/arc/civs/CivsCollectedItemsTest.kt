package ru.arc.civs

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.entity.Item
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.ItemEntityMock
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

class CivsCollectedItemsTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: CivsWorksiteWorldFixture
    lateinit var storage: CivsStorage
    lateinit var city: Settlement
    val control = CivPosition(0, 64, 0)
    val tiles = mutableListOf<CivPosition>()

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = CivsWorksiteWorldFixture("collected").also(paper.server::addWorld)
        world.loadChunk(0, 0)
        val plugin = paper.createSimplePlugin("CivsCollectedItemsTest")
        tiles.clear()
        storage = CivsStorage(plugin, PaperPlayerDataPersistence { }) { chunk ->
            tiles.filter { it.x shr 4 == chunk.x && it.z shr 4 == chunk.z }
                .map { world.getBlockAt(it.x, it.y, it.z).state }.toTypedArray()
        }.also { it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) } }
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, control, 0, "test",
            CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -2),
            BuildingPhase.ACTIVE, control = control)
        city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Collected", world.uid,
            control, 16, "nordic", Stock(), listOf(hall), listOf(CivWorker(UUID.randomUUID(), "Worker", null)),
            inventory = CivInventory(CivStorageMode.PHYSICAL)))
    }
    afterEach { storage.close(); paper.close() }

    fun receipt(plan: CivCollectedPlan, id: UUID): CivInventoryTransaction = CivInventoryTransaction(id, city.id, city.revision,
        city.copy(workers = listOf(plan.worker), revision = city.revision + 1), emptyList(), collected = plan.transfer)

    test("a consumed Item returning from an older chunk after receipt retirement cannot be collected again") {
        val folder = Files.createTempDirectory("civs-stale-ground-")
        try {
            val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
            val id = UUID.randomUUID()
            storage.reserve(city, id)
            val tx = receipt(storage.prepareGroundPickup(city, city.workers.single().id, listOf(item.uniqueId),
                Location(world, 2.5, 64.0, .5)), id)
            storage.seal(tx)
            val store = CivsStore(folder, Executor(Runnable::run))
            store.save(city).join()
            store.saveInventoryTransaction(tx).join()
            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            storage.persistCollected(tx).join()
            storage.installConsumed(store.markConsumedItems(tx).join())
            store.save(tx.after).join()
            store.deleteInventoryTransaction(tx.id).join()
            storage.unlock(tx.id)
            store.close()
            val reopened = CivsStore(folder, Executor(Runnable::run))
            val consumed = reopened.loadConsumedItems().join()
            reopened.loadInventoryTransactions().join() shouldBe emptyList()
            storage.close()
            storage = CivsStorage(paper.createSimplePlugin("ReloadedCollected")) { emptyArray() }
            storage.beginReceiptBootstrap()
            storage.completeReceiptBootstrap(emptyList(), consumed)
            // Simulate a stale disk copy from A after B and the receipt were already retired.
            world.loadChunk(-1, 0)
            fun oldCopy(): Item = CivsGroundItemEntityMock(paper.server, item.uniqueId, ItemStack(Material.COAL, 8)).apply {
                setLocation(Location(world, -3.5, 64.0, .5)); setOnGround(true); pickupDelay = 0
                paper.server.registerEntity(this)
            }
            val loaded = oldCopy()
            storage.onGroundAdded(EntityAddToWorldEvent(loaded, world))
            loaded.isValid shouldBe false
            val player = paper.addPlayer("OldChunkViewer")
            val pickup = oldCopy()
            val event = EntityPickupItemEvent(player, pickup, 0)
            storage.onPlayerPickup(event)
            event.isCancelled shouldBe true
            pickup.isValid shouldBe false
            val hopperCopy = oldCopy()
            val hopper = InventoryPickupItemEvent((world.getBlockAt(0, 64, 0).state as Barrel).inventory, hopperCopy)
            storage.onPickup(hopper)
            hopper.isCancelled shouldBe true
            hopperCopy.isValid shouldBe false
            val mergeCopy = oldCopy()
            val fresh = civsGroundFixture(paper, world, Location(world, -3.0, 64.0, .5), ItemStack(Material.COAL))
            val merge = ItemMergeEvent(mergeCopy, fresh)
            storage.onGroundMerge(merge)
            merge.isCancelled shouldBe true
            mergeCopy.isValid shouldBe false
            fresh.isValid shouldBe true
            val npcCopy = oldCopy()
            storage.reserve(tx.after, UUID.randomUUID())
            val denied = shouldThrow<CivsRejection> { storage.prepareGroundPickup(tx.after, city.workers.single().id,
                listOf(npcCopy.uniqueId), Location(world, -2.5, 64.0, .5)) }
            (denied.reason in setOf("ground-item-consumed", "stale-delivery")) shouldBe true
            reopened.load().join().single().workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
            reopened.close()
        } finally { folder.toFile().deleteRecursively() }
    }

    test("ground pickup merges exact bag metadata partially and never equips found gear") {
        val custom = ItemStack(Material.COAL, 10).apply { editMeta { it.displayName(Component.text("Named coal")) } }
        val slots = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { if (it >= CivsEquipment.BAG) CivResidentItem("STONE", 64) else null }
        slots[6] = CivResidentItem.of(custom.clone().apply { amount = 60 })
        val worker = city.workers.single().copy(items = slots)
        val (updated, moved) = CivsEquipment.collectGround(worker, requireNotNull(CivResidentItem.of(custom)))
        moved shouldBe 4
        updated.items!![6] shouldBe slots[6]!!.copy(amount = 64)
        updated.items!!.take(CivsEquipment.BAG) shouldBe slots.take(CivsEquipment.BAG)
        updated.hasCollectedItems shouldBe true
        CivsEquipment.collectGround(updated, CivResidentItem("COAL", 1)).second shouldBe 0
        val gear = CivsEquipment.collectGround(city.workers.single(), CivResidentItem("IRON_PICKAXE", 1)).first
        gear.items!![4] shouldBe null
        gear.items!![6] shouldBe CivResidentItem("IRON_PICKAXE", 1)
    }

    test("home surplus retains equipment spare tools food and work reserves with exact custom resources") {
        val custom = ItemStack(Material.COAL, 2).apply { editMeta { it.displayName(Component.text("Keeps metadata")) } }
        val worker = city.workers.single().copy(items = List(CivsEquipment.SLOTS) { slot -> when (slot) {
            4 -> CivResidentItem("DIAMOND_PICKAXE", 1)
            6 -> CivResidentItem("BREAD", 20)
            7 -> CivResidentItem("WHEAT_SEEDS", 20)
            8 -> CivResidentItem("IRON_HOE", 1)
            9 -> CivResidentItem.of(custom)
            else -> null
        } }, hasCollectedItems = true)
        CivsEquipment.homeSurplus(worker, mapOf("BREAD" to 16L, "WHEAT_SEEDS" to 16L)) shouldBe mapOf(6 to 4, 7 to 4, 9 to 2)
        CivsLogistics.workerReserve(city, worker) shouldBe mapOf("BREAD" to 16L)
    }

    test("native pickup leaves a partial fitting stack untouched until the whole stack fits") {
        val slots = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) {
            if (it >= CivsEquipment.BAG) CivResidentItem("STONE", 64) else null
        }
        slots[CivsEquipment.BAG] = CivResidentItem("COAL", 60)
        city = city.copy(workers = listOf(city.workers.single().copy(items = slots)))
        val worker = city.workers.single()
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val skipped = storage.prepareGroundPickup(city, worker.id, listOf(item.uniqueId), Location(world, 2.5, 64.0, .5))
        skipped.transfer shouldBe null
        skipped.worker shouldBe worker
        item.itemStack.amount shouldBe 8
        storage.unlock(id)
        city = city.copy(workers = listOf(worker.copy(items = slots.toMutableList().also { it[CivsEquipment.BAG] = CivResidentItem("COAL", 56) })))
        storage.reserve(city, id)
        val admitted = storage.prepareGroundPickup(city, worker.id, listOf(item.uniqueId), Location(world, 2.5, 64.0, .5))
        admitted.transfer!!.ground.single().after shouldBe null
        admitted.worker.items!![CivsEquipment.BAG] shouldBe CivResidentItem("COAL", 64)
        item.itemStack.amount shouldBe 8 // Planning still never performs the debit.
    }

    test("asynchronous source acknowledgement rechecks exact current Item and cancellation remains untouched") {
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = receipt(storage.prepareGroundPickup(city, city.workers.single().id, listOf(item.uniqueId),
            Location(world, 2.5, 64.0, .5)), id)
        storage.seal(tx)
        val native = CompletableFuture<Unit>()
        storage.persistCollectedNative = { _, _ -> native }
        val fence = storage.persistCollected(tx, beforeJournal = true)
        fence.isDone shouldBe false
        item.itemStack = ItemStack(Material.COAL, 9)
        native.complete(Unit)
        shouldThrow<CompletionException> { fence.join() }
        item.itemStack.amount shouldBe 9
        item.itemStack = ItemStack(Material.COAL, 8)
        val later = CompletableFuture<Unit>()
        storage.persistCollectedNative = { _, _ -> later }
        val cancelled = storage.persistCollected(tx, beforeJournal = true)
        storage.cancelCollectedPersistence()
        shouldThrow<CompletionException> { cancelled.join() }
        later.complete(Unit)
        item.isValid shouldBe true
        item.itemStack.amount shouldBe 8
    }

    for (queued in listOf(false, true)) test("lifecycle cancellation fences off-thread acknowledgement and admits a fresh epoch queued=$queued") {
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = receipt(storage.prepareGroundPickup(city, city.workers.single().id, listOf(item.uniqueId),
            Location(world, 2.5, 64.0, .5)), id)
        storage.seal(tx)
        fun acknowledge(native: CompletableFuture<Unit>) {
            CompletableFuture.runAsync { native.complete(Unit) }.get(2, TimeUnit.SECONDS)
        }
        val old = CompletableFuture<Unit>()
        storage.persistCollectedNative = { _, _ -> old }
        val cancelled = storage.persistCollected(tx, beforeJournal = true)
        if (queued) acknowledge(old)
        cancelled.isDone shouldBe false
        storage.cancelCollectedPersistence()
        cancelled.isCompletedExceptionally shouldBe true
        if (!queued) acknowledge(old)
        paper.performTicks(2)
        cancelled.isCompletedExceptionally shouldBe true
        item.isValid shouldBe true
        item.itemStack.amount shouldBe 8

        val fresh = CompletableFuture<Unit>()
        storage.persistCollectedNative = { _, _ -> fresh }
        val accepted = storage.persistCollected(tx, beforeJournal = true)
        acknowledge(fresh)
        accepted.isDone shouldBe false
        paper.performTicks(2)
        accepted.get(2, TimeUnit.SECONDS) shouldBe Unit
        item.isValid shouldBe true
        item.itemStack.amount shouldBe 8
    }

    for (autonomous in listOf(false, true)) test("ground receipt fences before-image and after-state and replays once autonomous=$autonomous") {
        if (autonomous) city = city.copy(inventory = null, stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
        val worker = city.workers.single()
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareGroundPickup(city, worker.id, listOf(item.uniqueId), Location(world, 2.5, 64.0, .5))
        val tx = receipt(plan, id)
        storage.seal(tx)
        val saved = mutableListOf<Boolean>()
        storage.persistCollectedNative = { _, _ -> saved += item.isValid; CompletableFuture.completedFuture(Unit) }
        storage.persistCollected(tx, beforeJournal = true).join()
        saved shouldBe listOf(true)
        item.itemStack.amount shouldBe 8
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.persistCollected(tx).join()
        saved shouldBe listOf(true, false)
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        tx.after.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
        tx.after.inventory shouldBe city.inventory
        tx.after.stock shouldBe city.stock
        storage.unlock(id)
        storage.lock(tx) // Restart: exact accepted endpoints, without a live resident.
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.persistCollected(tx).join()
        tx.after.workers.single().items!![6] shouldBe CivResidentItem("COAL", 8)
    }

    test("absent ground is retry in unloaded chunk and before-drift on a fresh apply") {
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = receipt(storage.prepareGroundPickup(city, city.workers.single().id, listOf(item.uniqueId),
            Location(world, 2.5, 64.0, .5)), id)
        storage.seal(tx)
        storage.persistCollected(tx, beforeJournal = true).join()
        item.remove()
        (storage.collectedBeforeState(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        world.unloadChunk(0, 0)
        (storage.reconcile(tx) is CivInventoryApplyResult.Retry) shouldBe true
        world.loadChunk(0, 0)
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
    }

    test("source drift or failed durable flush leaves every native endpoint untouched") {
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = receipt(storage.prepareGroundPickup(city, city.workers.single().id, listOf(item.uniqueId),
            Location(world, 2.5, 64.0, .5)), id)
        storage.seal(tx)
        storage.persistCollectedNative = { _, _ -> CompletableFuture.failedFuture(IllegalStateException("disk save failed")) }
        shouldThrow<CompletionException> { storage.persistCollected(tx, beforeJournal = true).join() }
        item.itemStack.amount shouldBe 8
        item.itemStack = item.itemStack.clone().apply { amount = 9 }
        (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        item.itemStack.amount shouldBe 9
    }

    test("accepted entity locks stop normal native pickup merge despawn damage and portals before journal IO") {
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        val other = civsGroundFixture(paper, world, Location(world, 3.6, 64.0, .5), ItemStack(Material.COAL, 8))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = receipt(storage.prepareGroundPickup(city, city.workers.single().id, listOf(item.uniqueId),
            Location(world, 2.5, 64.0, .5)), id)
        storage.seal(tx)
        val player = paper.addPlayer("Collector")
        val pickup = EntityPickupItemEvent(player, item, 0).also(storage::onPlayerPickup)
        pickup.isCancelled shouldBe true
        InventoryPickupItemEvent(player.inventory, item).also(storage::onPickup).isCancelled shouldBe true
        ItemMergeEvent(item, other).also(storage::onGroundMerge).isCancelled shouldBe true
        ItemMergeEvent(other, item).also(storage::onGroundMerge).isCancelled shouldBe true
        ItemDespawnEvent(item, item.location).also(storage::onGroundDespawn).isCancelled shouldBe true
        @Suppress("DEPRECATION")
        val damage = EntityDamageEvent(item, EntityDamageEvent.DamageCause.FIRE, 1.0).also(storage::onPlayerDamage)
        damage.isCancelled shouldBe true
        EntityTeleportEvent(item, item.location, item.location.clone().add(1.0, 0.0, 0.0))
            .also(storage::onGroundTeleport).isCancelled shouldBe true
        EntityPortalEvent(item, item.location, item.location.clone().add(1.0, 0.0, 0.0))
            .also(storage::onGroundPortal).isCancelled shouldBe true
        storage.unlock(id)
        EntityPickupItemEvent(player, item, 0).also(storage::onPlayerPickup).isCancelled shouldBe false
    }

    test("native reach rejects air walls distant items owners and both pickup permissions") {
        val feet = Location(world, 2.5, 64.0, .5)
        val worker = city.workers.single().id
        val item = civsGroundFixture(paper, world, Location(world, 3.5, 64.0, .5), ItemStack(Material.COAL, 8))
        civsCanCollect(feet, item, worker) shouldBe true
        item.setOnGround(false)
        civsCanCollect(feet, item, worker) shouldBe false
        item.setOnGround(true)
        item.owner = UUID.randomUUID()
        civsCanCollect(feet, item, worker) shouldBe false
        item.owner = null
        item.setCanMobPickup(false)
        civsCanCollect(feet, item, worker) shouldBe false
        item.setCanMobPickup(true)
        item.setCanPlayerPickup(false)
        civsCanCollect(feet, item, worker) shouldBe false
        item.setCanPlayerPickup(true)
        item.pickupDelay = 10
        civsCanCollect(feet, item, worker) shouldBe false
        item.pickupDelay = 0
        civsCanCollect(feet.clone().add(-4.0, 0.0, 0.0), item, worker) shouldBe false
        val nearClear = civsCanCollect(feet, item, worker)
        nearClear shouldBe true
        world.getBlockAt(3, 64, 0).type = Material.STONE
        civsCanCollect(feet, item, worker) shouldBe false
    }

    test("home deposit credits exact metadata partially preserving reserves and remainder in bag") {
        val custom = ItemStack(Material.COAL, 10).apply { editMeta { it.displayName(Component.text("Native cargo")) } }
        val worker = city.workers.single().copy(hasCollectedItems = true,
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem.of(custom) else if (it == 7) CivResidentItem("BREAD", 16) else null })
        city = city.copy(workers = listOf(worker), inventory = null, stock = CivsRules.STARTING_STOCK, autonomy = CivAutonomy())
        val barrel = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
        repeat(barrel.size) { barrel.setItem(it, ItemStack(Material.STONE, 64)) }
        barrel.setItem(0, custom.clone().apply { amount = 60 })
        storage.homeDestination(city, worker, mapOf("BREAD" to 16L)) shouldBe control
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val prepared = storage.prepareHomeDeposit(city, worker.id, control, Location(world, 1.5, 64.0, .5), mapOf("BREAD" to 16L))
        prepared.worker.items!![6] shouldBe CivResidentItem.of(custom.clone().apply { amount = 6 })
        prepared.worker.items!![7] shouldBe CivResidentItem("BREAD", 16)
        prepared.worker.hasCollectedItems shouldBe true
        val tx = receipt(prepared, id)
        storage.seal(tx)
        storage.persistCollected(tx, beforeJournal = true).join()
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        storage.persistCollected(tx).join()
        storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe custom.clone().apply { amount = 64 }
        tx.after.inventory shouldBe null
        tx.after.stock shouldBe city.stock
    }

    test("home deposit needs actual reachable container and clears marker only after all spare items fit") {
        val worker = city.workers.single().copy(hasCollectedItems = true,
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("COAL", 8) else null })
        city = city.copy(workers = listOf(worker))
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        shouldThrow<CivsRejection> { storage.prepareHomeDeposit(city, worker.id, control,
            Location(world, 8.5, 64.0, .5), emptyMap()) }.reason shouldBe "worker-too-far"
        val prepared = storage.prepareHomeDeposit(city, worker.id, control, Location(world, 1.5, 64.0, .5), emptyMap())
        prepared.worker.hasCollectedItems shouldBe false
        CivsEquipment.materials(prepared.worker) shouldBe emptyMap()
        val tx = receipt(prepared, id)
        storage.seal(tx)
        val barrel = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
        barrel.setItem(0, ItemStack(Material.DIAMOND))
        (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
        barrel.getItem(0) shouldBe ItemStack(Material.DIAMOND)
    }

    test("ordinary workshop and home interior racks are local storage while foreign controls and sites stay isolated") {
        val hall = city.buildings.single()
        val workshop = hall.copy(id = UUID.randomUUID(), type = BuildingType.BLACKSMITH,
            control = CivPosition(8, 64, 0), anchor = CivPosition(8, 64, 0), entrance = CivPosition(8, 64, -2),
            bounds = CivBox(CivPosition(6, 64, -2), CivPosition(12, 68, 2)))
        val rack = CivPosition(9, 64, 0)
        val foreign = workshop.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE, control = CivPosition(10, 64, 0),
            bounds = CivBox(CivPosition(13, 64, -2), CivPosition(15, 68, 2)))
        city = city.copy(buildings = listOf(hall, workshop, foreign))
        world.getBlockAt(8, 64, 0).type = Material.BARREL
        world.getBlockAt(9, 64, 0).type = Material.CHEST
        world.getBlockAt(10, 64, 0).type = Material.BARREL
        tiles += rack
        (world.getBlockAt(9, 64, 0).state as Chest).blockInventory.setItem(0, ItemStack(Material.IRON_INGOT, 6))
        (world.getBlockAt(10, 64, 0).state as Barrel).inventory.setItem(0, ItemStack(Material.DIAMOND, 7))
        storage.buildingAvailable(city, workshop.id) shouldBe mapOf("IRON_INGOT" to 6L)
        val home = workshop.copy(type = BuildingType.HOUSE)
        val worker = city.workers.single().copy(homeId = home.id, hasCollectedItems = true,
            items = List(CivsEquipment.SLOTS) { if (it == 6) CivResidentItem("IRON_INGOT", 1) else null })
        city = city.copy(buildings = listOf(hall, home, foreign), workers = listOf(worker))
        val homeBarrel = (world.getBlockAt(8, 64, 0).state as Barrel).inventory
        repeat(homeBarrel.size) { homeBarrel.setItem(it, ItemStack(Material.STONE, 64)) }
        storage.homeDestination(city, worker, emptyMap()) shouldBe rack
        storage.buildingAvailable(city.copy(buildings = listOf(hall, workshop.copy(phase = BuildingPhase.SITE), foreign)),
            workshop.id) shouldBe mapOf("STONE" to 1728L)
    }
})

/** Actual MockBukkit entity membership; only physical landing is explicit because it has no gravity simulation. */
internal fun civsGroundFixture(paper: MockBukkitTestRuntime, world: World, at: Location, stack: ItemStack): ItemEntityMock =
    CivsGroundItemEntityMock(paper.server, UUID.randomUUID(), stack.clone()).apply {
        setLocation(at)
        setOnGround(true)
        pickupDelay = 0
        setCanMobPickup(true)
        setCanPlayerPickup(true)
        paper.server.registerEntity(this)
    }

/** MockBukkit 4.116.3 leaves these Item methods unimplemented; keep only the fixture state they expose. */
private class CivsGroundItemEntityMock(
    server: org.mockbukkit.mockbukkit.ServerMock,
    uuid: UUID,
    stack: ItemStack,
) : ItemEntityMock(server, uuid, stack) {
    private var ownerId: UUID? = null
    private var mobPickup = true
    private var playerPickup = true

    override fun getOwner(): UUID? = ownerId
    override fun setOwner(owner: UUID?) { ownerId = owner }
    override fun canMobPickup(): Boolean = mobPickup
    override fun setCanMobPickup(canMobPickup: Boolean) { mobPickup = canMobPickup }
    override fun canPlayerPickup(): Boolean = playerPickup
    override fun setCanPlayerPickup(canPlayerPickup: Boolean) { playerPickup = canPlayerPickup }
}
