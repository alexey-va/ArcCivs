package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineReceiptTest : FunSpec({
    test("ordinary mine preparation and durable codec retain explicit authority while legacy receipts remain untagged") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("ordinary-mine-codec")
            val plugin = paper.createSimplePlugin("MineReceiptCodec")
            val (city, work) = mineReceiptFixture(world.uid)
            val root = Files.createTempDirectory("arc-civs-mine-receipt-")
            try {
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    val id = UUID.randomUUID()
                    storage.reserve(city, id)
                    val mutableSections = requireNotNull(work.miningSections).toMutableMap()
                    val prepared = storage.prepareWork(city, work.buildingId, work.storageInputs(city.workers.single()),
                        emptyMap(), work.changes, work.copy(miningSections = mutableSections))
                    mutableSections.clear()
                    prepared.mine!!.work.miningSections shouldBe work.miningSections
                    val tx = mineTransaction(id, city, work, prepared)
                    prepared.mine shouldBe tx.mine
                    tx.mine!!.before(tx.after).minePlans shouldBe city.minePlans
                    shouldThrow<IllegalArgumentException> {
                        tx.copy(playerDebit = CivPlayerDebit(city.owner, emptyList())).validated()
                    }
                    CivsStore(root, Executor(Runnable::run)).use { store ->
                        store.saveInventoryTransaction(tx).join() shouldBe tx
                        store.loadInventoryTransactions().join() shouldBe listOf(tx)
                        store.deleteInventoryTransaction(id).join() shouldBe true
                        val legacyCity = city.copy(minePlans = null)
                        val legacy = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
                            legacyCity.copy(revision = city.revision + 1), emptyList(), worldChanges = work.changes).validated()
                        Gson().toJsonTree(legacy).asJsonObject.has("mine") shouldBe false
                        store.saveInventoryTransaction(legacy).join() shouldBe legacy
                        store.loadInventoryTransactions().join().single().mine shouldBe null
                    }
                    storage.unlock(id)
                    val oldId = UUID.randomUUID()
                    storage.reserve(city, oldId)
                    val linear = work.copy(miningSections = null, miningAccessSections = null, miningOrdinal = null)
                    storage.prepareWork(city, work.buildingId, emptyMap(), emptyMap(), work.changes, linear).mine shouldBe null
                    storage.prepareWork(city, work.buildingId, emptyMap(), emptyMap(), work.changes).mine shouldBe null
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    for (alreadyApplied in listOf(false, true))
    test("ordinary mine authority gates container and world writes after restart with applied cell $alreadyApplied") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("mine-authority-$alreadyApplied")
            val plugin = paper.createSimplePlugin("MineReceiptAuthority")
            val (initial, work) = mineReceiptFixture(world.uid, supplies = false)
            val city = initial.copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
            val mine = requireNotNull(city.building(work.buildingId))
            val control = requireNotNull(mine.control)
            world.loadChunk(control.x shr 4, control.z shr 4)
            val holder = world.getBlockAt(control.x, control.y, control.z).also { it.type = Material.BARREL }
            val inventory = (holder.state as Barrel).inventory
            inventory.setItem(0, ItemStack(Material.COBBLESTONE, 4))
            val cell = work.changes.single()
            world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
            val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            block.blockData = Bukkit.createBlockData(cell.before)
            val tx = CivsStorage(plugin) { emptyArray() }.use { storage ->
                storage.replaceCities(listOf(city))
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val prepared = storage.prepareWork(city, mine.id, work.storageInputs(city.workers.single()),
                    emptyMap(), work.changes, work)
                prepared.changes.single().before shouldBe CivPlainItem("COBBLESTONE", 4)
                val receipt = mineTransaction(id, city, work, prepared)
                storage.seal(receipt)
                storage.validateMineNative = { CivInventoryApplyResult.Conflict(mapOf("reason" to "mine-claimed")) }
                (storage.reconcile(receipt) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "mine-claimed"
                inventory.getItem(0)!!.amount shouldBe 4
                block.blockData.asString shouldBe cell.before
                Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated()
            }
            if (alreadyApplied) block.blockData = Bukkit.createBlockData(cell.after)
            val recoveryState = block.blockData.asString
            CivsStorage(plugin) { emptyArray() }.use { recovered ->
                recovered.lock(tx)
                (recovered.reconcile(tx) as CivInventoryApplyResult.Conflict).values["reason"] shouldBe "mine-authority-unavailable"
                inventory.getItem(0)!!.amount shouldBe 4
                block.blockData.asString shouldBe recoveryState
                recovered.validateMineNative = { CivInventoryApplyResult.Retry(mapOf("reason" to "chunks-unloaded")) }
                (recovered.reconcile(tx) is CivInventoryApplyResult.Retry) shouldBe true
                inventory.getItem(0)!!.amount shouldBe 4
                block.blockData.asString shouldBe recoveryState
                var checks = 0
                recovered.validateMineNative = { checks++; null }
                recovered.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                recovered.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                checks shouldBe 2
                inventory.getItem(0)!!.amount shouldBe 3
                block.blockData.asString shouldBe cell.after
                tx.after.workers.single().completedCycles shouldBe city.workers.single().completedCycles + 1
            }
        }
    }

    test("native mine receipt validation accepts exact before and already applied backing without a planner cache") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-native-receipt").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineNativeReceipt")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val (city, work) = mineReceiptFixture(world.uid)
            val cell = work.changes.single()
            world.loadChunk(cell.position.x shr 4, cell.position.z shr 4)
            val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            try {
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val worksites = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    for (state in listOf(cell.before, cell.after)) {
                        block.blockData = Bukkit.createBlockData(state)
                        worksites.validateMineReceiptNative(city, work)
                        block.blockData.asString shouldBe state
                    }
                }
            } finally { tasks.close() }
        }
    }

    test("ordinary mine journal detaches nested before progress and action proof before queued persistence") {
        MockBukkitTestRuntime.open().use {
            val (initial, initialWork) = mineReceiptFixture(UUID.randomUUID())
            val key = initialWork.buildingId.toString()
            val beforeSections = linkedMapOf<String, Long>()
            val beforeSeals = linkedMapOf<String, String>()
            val previous = initial.minePlans!!.getValue(key).copy(sectionSteps = beforeSections, seals = beforeSeals)
            val city = initial.copy(minePlans = mapOf(key to previous))
            val sections = initialWork.miningSections!!.toMutableMap()
            val access = initialWork.miningAccessSections!!.toMutableMap()
            val inputs = initialWork.inputs.toMutableMap()
            val outputs = initialWork.outputs.toMutableMap()
            val cells = initialWork.changes.toMutableList()
            val deferred = linkedSetOf<CivPosition>()
            val work = initialWork.copy(changes = cells, inputs = inputs, outputs = outputs,
                miningSections = sections, miningAccessSections = access, miningDeferredSeals = deferred)
            val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision,
                CivsRules.finishWorldWork(city, work, 1_000), emptyList(), worldChanges = cells,
                mine = CivMineReceipt(requireNotNull(city.building(work.buildingId)), previous, null, work)).validated()
            val expected = tx.copy(worldChanges = cells.toList(), mine = tx.mine!!.detached())
            val pending = ArrayDeque<Runnable>()
            val root = Files.createTempDirectory("arc-civs-mine-snapshot-")
            try {
                CivsStore(root, Executor { pending.addLast(it) }).use { store ->
                    val saved = store.saveInventoryTransaction(tx)
                    beforeSections["invalid"] = 1
                    beforeSeals["invalid"] = "minecraft:diamond_block"
                    deferred.add(CivPosition(0, 0, 0))
                    sections.clear(); access["invalid"] = 9
                    inputs.clear(); outputs["DIAMOND"] = 64
                    cells.clear()
                    pending.removeFirst().run()
                    saved.join() shouldBe expected
                }
                CivsStore(root, Executor(Runnable::run)).use { store ->
                    store.loadInventoryTransactions().join() shouldBe listOf(expected)
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }
})

private fun mineReceiptFixture(worldId: UUID, supplies: Boolean = true): Pair<Settlement, CivWorldWork> {
    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
    val worker = spatialWorker(mine, "IRON_PICKAXE", supplies)
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    val ordinal = plan.sections.getValue("spine").first()
    val step = plan.steps[ordinal]
    require(step.after == "minecraft:cobblestone")
    val before = CivMinePlanState(CivsMinePlan.VERSION, 0, sectionSteps = emptyMap())
    val city = CivsRules.validate(spatialCity(worldId, listOf(mine), worker).copy(
        minePlans = mapOf(mine.id.toString() to before)))
    val sections = plan.advanceSection(ordinal, emptyMap())
    val point = CivsWorksiteLayouts.point(mine, step.point)
    val work = CivWorldWork(worker.id, worker.completedCycles, mine.id, mine.entrance,
        listOf(CivPlanBlock(point, "minecraft:air", requireNotNull(step.after))), inputs = mapOf("COBBLESTONE" to 1L),
        status = "digging-mine", durationMillis = 2_000, focus = point,
        miningStep = plan.normalizedProgress(0, sections).first, miningPlanVersion = CivsMinePlan.VERSION,
        miningAccessStep = 0, miningSections = sections, miningAccessSections = emptyMap(), miningOrdinal = ordinal)
    return city to work
}

private fun mineTransaction(id: UUID, city: Settlement, work: CivWorldWork, prepared: CivStoragePlan): CivInventoryTransaction =
    CivInventoryTransaction(id, city.id, city.revision,
        CivsRules.finishWorldWork(city, work, 1_000).copy(inventory = prepared.inventoryAfter), prepared.changes,
        worldChanges = prepared.worldChanges, mine = prepared.mine).validated()
