package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

class CivsWorksiteWarningsTest : FunSpec({
    test("a new structural quarry blocker warns within one tick of protection INFO while repeats and missing tools stay quiet") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("worksite-warning-transition").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("WorksiteWarningTransition")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val records = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { records += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            plugin.logger.addHandler(handler)
            try {
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                val old = requireNotNull(CivsMinePlan.forBuilding(mine, 2))
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val oldFinal = old.steps.associateBy { it.point }
                val index = geometry.steps.indexOfFirst { it.after == "minecraft:cobblestone" &&
                    oldFinal[it.point]?.let { previous -> !previous.shaftSupport && previous.after == "minecraft:cobblestone" } == true }
                (index >= 0) shouldBe true
                for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                for (step in old.steps + geometry.steps.take(index)) {
                    val point = CivsWorksiteLayouts.point(mine, step.point)
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                }
                val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
                // The curated miner1 authored column has exactly three rungs (-5..-3).
                for (dy in 0..4) {
                    val point = bottom.copy(y = bottom.y + dy)
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(
                        if (dy <= 2) "minecraft:ladder[facing=${geometry.facing},waterlogged=false]" else "minecraft:air")
                    val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = point.y)
                    world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
                }
                world.getBlockAt(bottom.x, bottom.y + 5, bottom.z).type = Material.DIRT
                // The retained V2 opening already has paid lining at the last committed shaft cut.
                // Otherwise its empty outer shell correctly offers a repair before inspecting the target.
                val previous = geometry.steps[index - 1].point
                ((previous.x to previous.z) in geometry.shaftColumns) shouldBe true
                for (y in previous.y..minOf(geometry.bottom.y, previous.y + 1)) {
                    for ((x, z) in geometry.shaftColumns) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val wall = CivPosition(x + dx, y, z + dz)
                        if ((wall.x to wall.z) in geometry.shaftColumns ||
                            geometry.builtStanding(wall, index.toLong()) ||
                            geometry.builtStanding(wall.copy(y = y - 1), index.toLong())) continue
                        val point = CivsWorksiteLayouts.point(mine, wall)
                        val block = world.getBlockAt(point.x, point.y, point.z)
                        if (block.type.isAir) block.type = Material.COBBLESTONE
                    }
                }
                val target = CivsWorksiteLayouts.point(mine, geometry.steps[index].point)
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                var city = CivsRules.validate(spatialCity(world.uid, listOf(mine), worker).copy(
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, index.toLong(), v2Step = 281))))
                var locked = true
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> locked })
                    repeat(128) {
                        if (runtime.idleReason(city, worker) != "worksite-protected") runtime.pulse(listOf(city))
                    }
                    runtime.plan(city, worker) shouldBe null
                    runtime.idleReason(city, worker) shouldBe "worksite-protected"
                    val info = records.single { "event=worksite_blocked" in it.message }
                    info.level shouldBe Level.INFO
                    ("reason=world-operation-locked" in info.message) shouldBe true
                    records.count { it.level == Level.WARNING } shouldBe 0
                    val infoTick = Bukkit.getCurrentTick()

                    paper.performTicks(1)
                    locked = false
                    world.getBlockAt(target.x, target.y, target.z).type = Material.DIAMOND_BLOCK
                    runtime.invalidate(worker.id)
                    repeat(128) {
                        if (records.none { record -> record.level == Level.WARNING }) runtime.pulse(listOf(city))
                    }

                    Bukkit.getCurrentTick() - infoTick shouldBe 1
                    runtime.plan(city, worker) shouldBe null
                    runtime.idleReason(city, worker) shouldBe "worksite-obstacle"
                    val warning = records.single { it.level == Level.WARNING }
                    ("event=worksite_blocked" in warning.message) shouldBe true
                    ("status=worksite-obstacle" in warning.message) shouldBe true
                    ("reason=legacy-cell-changed" in warning.message) shouldBe true
                    ("material=DIAMOND_BLOCK" in warning.message) shouldBe true
                    ("city=${city.id}" in warning.message) shouldBe true
                    ("worker=${worker.id}" in warning.message) shouldBe true
                    ("building=${mine.id}" in warning.message) shouldBe true
                    (target.toString() in warning.message) shouldBe true

                    repeat(8) { runtime.pulse(listOf(city)) }
                    paper.performTicks(100)
                    repeat(8) { runtime.pulse(listOf(city)) }
                    records.count { it.level == Level.WARNING } shouldBe 1
                    records.count { "event=worksite_blocked" in it.message } shouldBe 2
                    world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.DIAMOND_BLOCK
                    city.workers.single().completedCycles shouldBe 0L
                    city.workers.single().items shouldBe worker.items

                    val missingTool = worker.copy(items = CivsEquipment.slots(worker).toMutableList().also { it[4] = null })
                    city = CivsRules.validate(city.copy(workers = listOf(missingTool)))
                    runtime.invalidate(worker.id)
                    repeat(8) { runtime.pulse(listOf(city)) }
                    runtime.plan(city, missingTool) shouldBe null
                    runtime.idleReason(city, missingTool) shouldBe "waiting-tool"
                    records.count { it.level == Level.WARNING } shouldBe 1
                    records.count { "event=worksite_blocked" in it.message } shouldBe 2
                }
            } finally {
                plugin.logger.removeHandler(handler)
                tasks.close()
            }
        }
    }
})
