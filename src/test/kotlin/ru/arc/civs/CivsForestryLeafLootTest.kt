package ru.arc.civs

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Barrel
import org.bukkit.block.BlockFace
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.entity.ItemEntityMock
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class CivsForestryLeafLootTest : FunSpec({
    test("native decay Item enters only its forester holder once through persisted UUID receipt") {
        LeafLootFixture().use { fixture -> with(fixture) {
            val item = decay()
            val proof = requireNotNull(CivsForestryLeafLoot.proof(item))
            proof.sources.single().leaf shouldBe leaf
            item.willAge() shouldBe false
            val merge = ItemMergeEvent(item, ground(Material.APPLE, 1))
            loot.onMerge(merge)
            merge.isCancelled shouldBe true
            val id = UUID.randomUUID()
            storage.reserve(city, id)
            val planned = storage.prepareForestryLeafLoot(city, worker.id, listOf(item.uniqueId))
            planned.worker shouldBe worker
            val tx = transaction(planned, id).validated()
            storage.seal(tx)
            storage.collectedBeforeState(tx) shouldBe CivInventoryApplyResult.Applied
            val folder = Files.createTempDirectory("civs-leaf-receipt-")
            try {
                CivsStore(folder, Executor(Runnable::run)).use { store ->
                    store.save(city).join()
                    store.saveInventoryTransaction(tx).join()
                    val replay = store.loadInventoryTransactions().join().single().validated()
                    replay.collected!!.forestryLeafLoot shouldBe tx.collected!!.forestryLeafLoot
                    repeat(2) { storage.reconcile(replay) shouldBe CivInventoryApplyResult.Applied }
                    holder.inventory.getItem(0) shouldBe ItemStack(Material.APPLE, 2)
                    item.isValid shouldBe false
                    replay.after.stock shouldBe city.stock
                    replay.after.workers shouldBe city.workers
                    storage.persistCollected(replay).join()
                    storage.installConsumed(store.markConsumedItems(replay).join())
                    store.save(replay.after).join()
                    store.deleteInventoryTransaction(replay.id).join() shouldBe true
                    storage.unlock(id)
                    val stale = ground(Material.APPLE, 2, item.uniqueId)
                    CivsForestryLeafLoot.mark(stale, proof)
                    storage.onGroundAdded(EntityAddToWorldEvent(stale, world))
                    stale.isValid shouldBe false
                    holder.inventory.getItem(0) shouldBe ItemStack(Material.APPLE, 2)
                }
            } finally { folder.toFile().deleteRecursively() }
        } }
    }

    test("full holder source and destination Lands denial and lost native provenance leave the Item untouched") {
        LeafLootFixture().use { fixture -> with(fixture) {
            val item = decay()
            val proof = requireNotNull(CivsForestryLeafLoot.proof(item))
            for (slot in 0..26) holder.inventory.setItem(slot, ItemStack(Material.STONE, 64))
            var id = UUID.randomUUID()
            storage.reserve(city, id)
            storage.prepareForestryLeafLoot(city, worker.id, listOf(item.uniqueId)).transfer shouldBe null
            item.itemStack shouldBe ItemStack(Material.APPLE, 2)
            item.willAge() shouldBe false
            storage.unlock(id)
            holder.inventory.clear()
            denied = leaf
            id = UUID.randomUUID()
            storage.reserve(city, id)
            shouldThrow<CivsRejection> { storage.prepareForestryLeafLoot(city, worker.id, listOf(item.uniqueId)) }.reason shouldBe "lands-denied"
            storage.unlock(id)
            denied = null
            id = UUID.randomUUID()
            storage.reserve(city, id)
            val tx = transaction(storage.prepareForestryLeafLoot(city, worker.id, listOf(item.uniqueId)), id).validated()
            storage.seal(tx)
            denied = requireNotNull(mill.control)
            (storage.reconcile(tx) is CivInventoryApplyResult.Retry) shouldBe true
            item.isValid shouldBe true
            holder.inventory.isEmpty shouldBe true
            denied = null
            city = city.copy(owner = UUID.randomUUID())
            (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
            city = city.copy(owner = proof.ownerId)
            item.persistentDataContainer.remove(NamespacedKey("arccivs", "forestry_leaf_source"))
            (storage.reconcile(tx) is CivInventoryApplyResult.Conflict) shouldBe true
            item.isValid shouldBe true
            holder.inventory.isEmpty shouldBe true
            CivsForestryLeafLoot.mark(item, proof)
            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            holder.inventory.getItem(0) shouldBe ItemStack(Material.APPLE, 2)
        } }
    }

    test("cancelled decay outside forest and player thrown or custom Items gain no leaf authority") {
        LeafLootFixture().use { fixture -> with(fixture) {
            val block = world.getBlockAt(leaf.x, leaf.y, leaf.z)
            val cancelled = LeavesDecayEvent(block).also { it.isCancelled = true }
            // Direct invocation models dispatch's ignoreCancelled fence explicitly.
            if (!cancelled.isCancelled) loot.onDecay(cancelled)
            val cancelledDrop = atLeaf(Material.APPLE, 1)
            loot.onSpawn(ItemSpawnEvent(cancelledDrop))
            CivsForestryLeafLoot.proof(cancelledDrop) shouldBe null
            val outside = leaf.copy(x = 70)
            world.getBlockAt(outside.x, outside.y, outside.z).type = Material.OAK_LEAVES
            loot.onDecay(LeavesDecayEvent(world.getBlockAt(outside.x, outside.y, outside.z)))
            val outsideDrop = atLeaf(Material.APPLE, 1).also { it.setLocation(Location(world, outside.x + .5, outside.y + .5, outside.z + .5)) }
            loot.onSpawn(ItemSpawnEvent(outsideDrop))
            CivsForestryLeafLoot.proof(outsideDrop) shouldBe null
            loot.onDecay(LeavesDecayEvent(block))
            val thrown = atLeaf(Material.APPLE, 1).also { it.thrower = UUID.randomUUID() }
            loot.onSpawn(ItemSpawnEvent(thrown))
            CivsForestryLeafLoot.proof(thrown) shouldBe null
            val custom = atLeaf(Material.APPLE, 1).also { it.itemStack = it.itemStack.apply { editMeta { it.setCustomModelData(42) } } }
            loot.onSpawn(ItemSpawnEvent(custom))
            CivsForestryLeafLoot.proof(custom) shouldBe null
            val diamond = atLeaf(Material.DIAMOND, 1)
            loot.onSpawn(ItemSpawnEvent(diamond))
            CivsForestryLeafLoot.proof(diamond) shouldBe null
            holder.inventory.isEmpty shouldBe true
        } }
    }

    test("persistent source reload is rediscovered and owner removal restores ordinary Item ageing") {
        LeafLootFixture().use { fixture -> with(fixture) {
            val item = decay()
            var picked = emptyList<UUID>()
            val reopened = CivsForestryLeafLoot({ mapOf(city.id to city) }, { true }, { _, _ -> true }, { true }, { _, _, ids ->
                picked = ids; CompletableFuture.completedFuture(city)
            })
            reopened.onAdded(EntityAddToWorldEvent(item, world))
            reopened.pulse()
            picked shouldBe listOf(item.uniqueId)
            city = city.copy(owner = UUID.randomUUID())
            reopened.pulse()
            CivsForestryLeafLoot.proof(item) shouldBe null
            item.willAge() shouldBe true
        } }
    }

    test("an accepted crown outside search columns keeps its exact source after the tree job finishes") {
        LeafLootFixture().use { fixture -> with(fixture) {
            val root = CivPosition(26, 64, 10)
            val crown = root.copy(x = 35, y = 67)
            val logCode = (1 shl 16) + 15 + (15 shl 5)
            val leafCode = 24 + (15 shl 5) + (3 shl 10)
            val tree = CivForestryJob(UUID.randomUUID(), root, "OAK_SAPLING", listOf(root),
                listOf("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", "minecraft:oak_log[axis=y]"),
                listOf(leafCode, logCode))
            CivsForestry.valid(city, mill.id.toString(), tree) shouldBe true
            city = city.copy(forestryJobs = mapOf(mill.id.toString() to tree))
            world.getBlockAt(crown.x, crown.y, crown.z).blockData = Bukkit.createBlockData(tree.states.first())
            loot.onDecay(LeavesDecayEvent(world.getBlockAt(crown.x, crown.y, crown.z)))
            val item = ground(Material.OAK_SAPLING, 1).also {
                it.setLocation(Location(world, crown.x + .5, crown.y + .5, crown.z + .5))
            }
            loot.onSpawn(ItemSpawnEvent(item))
            val proof = requireNotNull(CivsForestryLeafLoot.proof(item))
            proof.sources.single().treeId shouldBe tree.id
            proof.sources.single().crownRoot shouldBe root
            CivsForestryLeafLoot.current(proof, city.copy(forestryJobs = null)) shouldBe true
        } }
    }

    test("unloaded native source and forged generic or foreign-container receipts cannot move leaf goods") {
        LeafLootFixture().use { fixture -> with(fixture) {
            val item = decay()
            val id = UUID.randomUUID()
            storage.reserve(city, id)
            val tx = transaction(storage.prepareForestryLeafLoot(city, worker.id, listOf(item.uniqueId)), id).validated()
            val transfer = requireNotNull(tx.collected)
            shouldThrow<IllegalArgumentException> { transfer.copy(forestryLeafLoot = null).validated(tx.after) }
            val hall = city.buildings.single { it.type == BuildingType.TOWN_HALL }
            shouldThrow<IllegalArgumentException> { transfer.copy(containers = transfer.containers.map {
                it.copy(container = requireNotNull(hall.control))
            }).validated(tx.after) }
            shouldThrow<IllegalArgumentException> { transfer.copy(forestryLeafLoot = transfer.forestryLeafLoot!!.copy(
                sources = listOf(transfer.forestryLeafLoot!!.sources.single().copy(entityId = UUID.randomUUID())))).validated(tx.after) }
            storage.seal(tx)
            world.unloadChunk(item.location.blockX shr 4, item.location.blockZ shr 4)
            (storage.reconcile(tx) is CivInventoryApplyResult.Retry) shouldBe true
            item.isValid shouldBe true
            holder.inventory.isEmpty shouldBe true
            world.loadChunk(item.location.blockX shr 4, item.location.blockZ shr 4)
            storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            holder.inventory.getItem(0) shouldBe ItemStack(Material.APPLE, 2)
        } }
    }

    for (auto in listOf(false, true)) test("ordinary and Auto PHYSICAL prepare the same native leaf transfer with Auto $auto") {
        LeafLootFixture().use { fixture -> with(fixture) {
            if (auto) city = city.copy(autonomy = CivAutonomy())
            val item = decay()
            val id = UUID.randomUUID()
            storage.reserve(city, id)
            val plan = storage.prepareForestryLeafLoot(city, worker.id, listOf(item.uniqueId))
            plan.worker shouldBe worker
            plan.stock shouldBe null
            plan.transfer!!.validated(city.copy(revision = city.revision + 1))
            plan.transfer!!.ground.single().entityId shouldBe item.uniqueId
            plan.transfer!!.containers.single().after shouldBe CivResidentItem("APPLE", 2)
            item.itemStack shouldBe ItemStack(Material.APPLE, 2)
            holder.inventory.isEmpty shouldBe true
        } }
    }

    test("native felling samples leaf drops once and retains sapling apple and stick in ordinary outputs") {
        MockBukkitTestRuntime.open().use { paper ->
            val root = CivPosition(10, 64, 10)
            val leaf = root.copy(y = 67)
            var leafBlock: BlockMock? = null
            var samples = 0
            val world = object : CivsWorksiteWorldFixture("native-leaf-felling", Material.DIRT) {
                override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock =
                    if (CivPosition(x, y, z) == leaf && leafBlock != null) requireNotNull(leafBlock) else super.getBlockAt(x, y, z)
            }.also(paper.server::addWorld)
            leafBlock = object : BlockMock(Material.OAK_LEAVES, Location(world, leaf.x.toDouble(), leaf.y.toDouble(), leaf.z.toDouble())) {
                override fun getDrops(tool: ItemStack?): Collection<ItemStack> {
                    tool?.type shouldBe Material.IRON_AXE
                    samples++
                    return listOf(ItemStack(Material.OAK_SAPLING), ItemStack(Material.APPLE, 2), ItemStack(Material.STICK, 3))
                }
            }.also { it.blockData = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]") }
            for (y in root.y..root.y + 2) world.getBlockAt(root.x, y, root.z).type = Material.OAK_LOG
            val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0))
            val worker = CivsEquipment.collectSupplies(spatialWorker(mill, "IRON_AXE"), mapOf("OAK_SAPLING" to 1L))
            val city = spatialCity(world.uid, listOf(mill), worker)
            for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
            val plugin = paper.createSimplePlugin("NativeLeafFelling")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                    repeat(1_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
                    val work = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
                    work.status shouldBe "felling-tree"
                    work.outputs shouldBe mapOf("OAK_SAPLING" to 1L, "APPLE" to 2L, "STICK" to 3L, "OAK_LOG" to 3L)
                    repeat(20) { runtime.plan(city, worker) shouldBe work }
                    samples shouldBe 1
                    val after = CivsRules.finishWorldWork(city, work, 1_000)
                    CivsEquipment.plainCount(after.workers.single(), "APPLE") shouldBe 2
                    CivsEquipment.plainCount(after.workers.single(), "STICK") shouldBe 3
                    CivsEquipment.plainCount(after.workers.single(), "OAK_SAPLING") shouldBe 1
                }
            }
        }
    }
})

private class LeafLootFixture : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val world = CivsWorksiteWorldFixture("leaf-loot", Material.DIRT).also(paper.server::addWorld)
    val plugin = paper.createSimplePlugin("LeafLoot")
    val mill = spatialBuilding("nordic", "lumberjack", 1, CivPosition(0, 63, 0))
    val worker = spatialWorker(mill, "IRON_AXE")
    var city = spatialCity(world.uid, listOf(mill), worker).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
    val leaf = CivPosition(10, 67, 10)
    var denied: CivPosition? = null
    val holder: Barrel
    val storage: CivsStorage
    val loot = CivsForestryLeafLoot({ mapOf(city.id to city) }, { true }, { _, point -> point != denied }, { false }, { _, _, _ ->
        error("No service pickup is expected in this fixture")
    })
    init {
        for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
        val control = requireNotNull(mill.control)
        world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
        holder = world.getBlockAt(control.x, control.y, control.z).state as Barrel
        storage = CivsStorage(plugin, PaperPlayerDataPersistence { }) { chunk ->
            if (control.x shr 4 == chunk.x && control.z shr 4 == chunk.z) arrayOf(holder) else emptyArray()
        }.also {
            it.foodCanModify = { _, point -> point != denied && world.isChunkLoaded(point.x shr 4, point.z shr 4) }
            it.forestryLeafCurrentCity = { id -> city.takeIf { it.id == id } }
            it.persistCollectedNative = { _, _ -> CompletableFuture.completedFuture(Unit) }
            it.replaceCities(listOf(city))
        }
        world.getBlockAt(leaf.x, leaf.y, leaf.z).blockData = Bukkit.createBlockData("minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]")
    }
    fun atLeaf(material: Material, amount: Int) = leafItem(paper.server, world, Location(world, leaf.x + .5, leaf.y + .5, leaf.z + .5), material, amount)
    fun ground(material: Material, amount: Int, uuid: UUID = UUID.randomUUID()) =
        leafItem(paper.server, world, Location(world, leaf.x + .5, 64.0, leaf.z + .5), material, amount, uuid)
    fun decay(): LeafItemFixture {
        loot.onDecay(LeavesDecayEvent(world.getBlockAt(leaf.x, leaf.y, leaf.z)))
        val item = atLeaf(Material.APPLE, 2)
        loot.onSpawn(ItemSpawnEvent(item))
        world.getBlockAt(leaf.x, leaf.y, leaf.z).type = Material.AIR
        item.setLocation(Location(world, leaf.x + .5, 64.0, leaf.z + .5))
        return item
    }
    fun transaction(plan: CivCollectedPlan, id: UUID) = CivInventoryTransaction(id, city.id, city.revision,
        city.copy(workers = listOf(plan.worker), revision = city.revision + 1), emptyList(), collected = plan.transfer)
    override fun close() { storage.close(); paper.close() }
}

private fun leafItem(server: ServerMock, world: org.bukkit.World, at: Location, material: Material, amount: Int,
    uuid: UUID = UUID.randomUUID()) = LeafItemFixture(server, uuid, ItemStack(material, amount)).apply {
    setLocation(at); setOnGround(true); pickupDelay = 0; server.registerEntity(this)
}

internal class LeafItemFixture(server: ServerMock, uuid: UUID, stack: ItemStack) : ItemEntityMock(server, uuid, stack) {
    private var ownerId: UUID? = null
    private var throwerId: UUID? = null
    private var ageing = true
    override fun getOwner() = ownerId
    override fun setOwner(owner: UUID?) { ownerId = owner }
    override fun getThrower() = throwerId
    override fun setThrower(thrower: UUID?) { throwerId = thrower }
    override fun canMobPickup() = true
    override fun canPlayerPickup() = true
    override fun setWillAge(willAge: Boolean) { ageing = willAge }
    override fun willAge() = ageing
}
