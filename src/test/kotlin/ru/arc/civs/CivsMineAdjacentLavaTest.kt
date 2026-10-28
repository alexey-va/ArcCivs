package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineAdjacentLavaTest : FunSpec({
    test("one adjacent lava cell can use its exact reachable rock frontier as the physical focus") {
        adjacentLavaFixture { _, _, _, _, _, work ->
            citizenWorkFocus(work) shouldBe work.focus
            citizenWorkFocus(work.copy(focus = work.focus!!.copy(x = work.focus.x + 8))) shouldBe null
            citizenWorkFocus(work.copy(miningFrontierBefore = null)) shouldBe null
            citizenWorkFocus(work.copy(outputs = mapOf("COBBLESTONE" to 1L))) shouldBe null
        }
    }

    test("adjacent lava receipt revalidates the untouched frontier before removing any liquid") {
        adjacentLavaFixture { _, world, runtime, city, mine, work ->
            runtime.validateMineReceiptNative(city, work)
            CivsMineProgress.after(mine, city.minePlans!!.getValue(mine.id.toString()), null, work) shouldBe
                city.minePlans.getValue(mine.id.toString())
            val focus = requireNotNull(work.focus)
            world.getBlockAt(focus.x, focus.y, focus.z).type = Material.DIORITE
            shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }.reason shouldBe "stale-worksite"
            val liquid = work.changes.single().position
            world.getBlockAt(liquid.x, liquid.y, liquid.z).type shouldBe Material.LAVA
            city.workers.single().completedCycles shouldBe 0
            city.stock shouldBe Stock()
        }
    }

    test("adjacent lava permission keeps loaded locks and Lands and never clears water") {
        var locked: CivPosition? = null
        adjacentLavaFixture(locked = { it == locked }) { paper, world, runtime, city, _, work ->
            locked = work.focus
            shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }.reason shouldBe "protected-area"
            locked = null
            val source = work.changes.single().position
            world.unloadChunk(source.x shr 4, source.z shr 4)
            shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }.reason shouldBe "chunks-unloaded"
            world.loadChunk(source.x shr 4, source.z shr 4)
            world.getBlockAt(source.x, source.y, source.z).type = Material.WATER
            val water = work.copy(changes = listOf(work.changes.single().copy(before = "minecraft:water[level=0]")))
            shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, water) }
            world.getBlockAt(source.x, source.y, source.z).type shouldBe Material.WATER
            world.getBlockAt(source.x, source.y, source.z).blockData = Bukkit.createBlockData(work.changes.single().before)
            val lands = paper.createSimplePlugin("Lands")
            paper.server.pluginManager.disablePlugin(lands)
            shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }
            world.getBlockAt(source.x, source.y, source.z).type shouldBe Material.LAVA
        }
    }

    for (applied in listOf(false, true)) test("adjacent lava proof survives sealed restart without items or cut credit applied=$applied") {
        adjacentLavaFixture { paper, world, runtime, city, mine, work ->
            val after = CivsRules.finishWorldWork(city, work, 10_000)
            val directory = Files.createTempDirectory("arc-civs-adjacent-lava-")
            try {
                val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                    worldChanges = work.changes, mine = CivMineReceipt(mine, city.minePlans!!.getValue(mine.id.toString()), null, work)).validated()
                Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated() shouldBe tx
                CivsStore(directory, Executor(Runnable::run)).use { store -> store.saveInventoryTransaction(tx).join() shouldBe tx }
                val source = work.changes.single().position
                if (applied) world.getBlockAt(source.x, source.y, source.z).type = Material.AIR
                CivsStore(directory, Executor(Runnable::run)).use { restarted ->
                    val accepted = restarted.loadInventoryTransactions().join().single()
                    CivsStorage(requireNotNull(paper.server.pluginManager.getPlugin("MineAdjacentLava"))) { emptyArray() }.use { storage ->
                        storage.lock(accepted)
                        storage.validateMineNative = { transaction ->
                            val receipt = requireNotNull(transaction.mine)
                            runtime.validateMineReceiptNative(receipt.before(transaction.after), receipt.work)
                            null
                        }
                        storage.reconcile(accepted) shouldBe CivInventoryApplyResult.Applied
                        storage.reconcile(accepted) shouldBe CivInventoryApplyResult.Applied
                    }
                    accepted.after.minePlans shouldBe city.minePlans
                    accepted.after.workers.single().items shouldBe city.workers.single().items
                    accepted.after.stock shouldBe city.stock
                    world.getBlockAt(source.x, source.y, source.z).type shouldBe Material.AIR
                    val frontier = requireNotNull(work.focus)
                    world.getBlockAt(frontier.x, frontier.y, frontier.z).type shouldBe Material.STONE
                }
            } finally { directory.toFile().deleteRecursively() }
        }
    }
})

private fun adjacentLavaFixture(locked: (CivPosition) -> Boolean = { false }, body: (ru.arc.paper.testing.MockBukkitTestRuntime, CivsWorksiteWorldFixture,
    CivsWorksites, Settlement, CivBuilding, CivWorldWork) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("mine-adjacent-lava").also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("MineAdjacentLava")
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 45, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val worker = spatialWorker(mine, "IRON_PICKAXE")
        for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
        val floor = plan.floors.last()
        val ordinal = plan.steps.indices.first { plan.steps[it].floor == floor.index && plan.steps[it].after == null &&
            (plan.steps[it].point.x to plan.steps[it].point.z) !in plan.shaftColumns }
        val local = plan.steps[ordinal].point
        val frontier = CivsWorksiteLayouts.point(mine, local)
        val source = listOf(local.copy(x = local.x + 1), local.copy(x = local.x - 1),
            local.copy(z = local.z + 1), local.copy(z = local.z - 1)).first { it in plan.plannedCuts && (it.x to it.z) !in plan.shaftColumns }
        val liquid = CivsWorksiteLayouts.point(mine, source)
        for (index in 0 until ordinal) {
            val point = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
            world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
        }
        for (y in plan.bottom.y..plan.bottom.y + 2) {
            val rung = CivsWorksiteLayouts.point(mine, plan.bottom.copy(y = y))
            val backing = CivsWorksiteLayouts.point(mine, plan.backing.copy(y = y))
            world.getBlockAt(rung.x, rung.y, rung.z).blockData = Bukkit.createBlockData("minecraft:ladder[facing=${plan.facing},waterlogged=false]")
            world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
        }
        val saved = CivMinePlanState(plan.version, ordinal.toLong(), sectionSteps = emptyMap())
        val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to saved))
        val work = CivWorldWork(worker.id, 0, mine.id, CivsWorksiteLayouts.point(mine, requireNotNull(plan.steps[ordinal].approach)),
            listOf(CivPlanBlock(liquid, "minecraft:lava[level=0]", "minecraft:air")),
            status = "securing-mine", miningPlanVersion = plan.version, miningStep = saved.nextStep, miningAccessStep = saved.nextStep,
            miningSections = saved.sectionSteps, miningAccessSections = saved.sectionSteps, miningOrdinal = ordinal,
            miningFrontier = local, miningExcavation = local, focus = frontier, miningFrontierBefore = "minecraft:stone")
        world.getBlockAt(liquid.x, liquid.y, liquid.z).blockData = Bukkit.createBlockData(work.changes.single().before)
        LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, point -> locked(point) }, nanoTime = { 0L })
                body(paper, world, runtime, city, mine, work)
            }
        }
    }
}
