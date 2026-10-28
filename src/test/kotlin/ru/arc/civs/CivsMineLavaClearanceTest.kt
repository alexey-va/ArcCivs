package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineLavaClearanceTest : FunSpec({
    test("lava clearance uses one unchanged-cursor receipt and cannot credit stone or clear water") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-lava-clearance").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineLavaClearance")
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 45, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            val before = CivMinePlanState(7, 0, sectionSteps = emptyMap())
            val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to before))
            val local = plan.steps.first().point
            val point = CivsWorksiteLayouts.point(mine, local)
            val work = CivWorldWork(worker.id, worker.completedCycles, mine.id, mine.entrance,
                listOf(CivPlanBlock(point, "minecraft:lava[level=0]", "minecraft:air")),
                status = "securing-mine", miningPlanVersion = 7, miningStep = 0, miningAccessStep = 0,
                miningSections = emptyMap(), miningAccessSections = emptyMap(), miningOrdinal = 0,
                miningFrontier = local, miningExcavation = local)
            for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
            val block = world.getBlockAt(point.x, point.y, point.z)
            block.blockData = Bukkit.createBlockData(work.changes.single().before)
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    runtime.validateMineReceiptNative(city, work)
                    CivsMineProgress.after(mine, before, null, work) shouldBe before
                    val after = CivsRules.finishWorldWork(city, work, 1_000)
                    after.workers.single().items shouldBe worker.items
                    after.stock shouldBe city.stock
                    after.workers.single().pendingStock shouldBe null
                    after.minePlans!!.getValue(mine.id.toString()) shouldBe before
                    block.type shouldBe Material.LAVA
                    shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
                        work.copy(outputs = mapOf("COBBLESTONE" to 1L))) }
                    shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null,
                        work.copy(changes = listOf(work.changes.single().copy(position = point.copy(x = point.x + 70))))) }
                    block.type = Material.WATER
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city,
                        work.copy(changes = listOf(work.changes.single().copy(before = block.blockData.asString)))) }
                    block.blockData = Bukkit.createBlockData(work.changes.single().before)
                    val lands = paper.createSimplePlugin("Lands")
                    paper.server.pluginManager.disablePlugin(lands)
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }
                    block.type shouldBe Material.LAVA
                }
            }
        }
    }

    test("the lava permission retains the native surface cover gate") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-lava-shallow-cover").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineLavaCover")
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 70, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            val city = spatialCity(world.uid, listOf(mine), worker)
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            val ordinal = plan.steps.indices.first { plan.steps[it].stage == "gallery" && plan.steps[it].after == null }
            val local = plan.steps[ordinal].point
            val point = CivsWorksiteLayouts.point(mine, local)
            world.loadChunk(point.x shr 4, point.z shr 4)
            world.getBlockAt(point.x, point.y, point.z).type = Material.LAVA
            val work = CivWorldWork(worker.id, worker.completedCycles, mine.id, mine.entrance,
                listOf(CivPlanBlock(point, world.getBlockAt(point.x, point.y, point.z).blockData.asString, "minecraft:air")),
                status = "securing-mine", miningPlanVersion = 7, miningStep = 0, miningAccessStep = 0,
                miningSections = emptyMap(), miningAccessSections = emptyMap(), miningOrdinal = ordinal,
                miningFrontier = local, miningExcavation = local)
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    shouldThrow<CivsRejection> { runtime.validateMineReceiptNative(city, work) }.reason shouldBe "mine-thin-cover"
                    world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.LAVA
                }
            }
        }
    }
})
