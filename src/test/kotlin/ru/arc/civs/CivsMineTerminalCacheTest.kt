package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime

class CivsMineTerminalCacheTest : FunSpec({
    test("an unchanged liquid branch stops native discovery and real world or protection changes reopen it") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            var reads = 0
            val world = object : CivsWorksiteWorldFixture("mine-terminal-cache") {
                override fun getBlockAt(x: Int, y: Int, z: Int) = super.getBlockAt(x, y, z).also { reads++ }
            }.also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineTerminalCache")
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 45, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            var city = spatialCity(world.uid, listOf(mine), worker).copy(inventory = null, autonomy = CivAutonomy())
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
            val rung = plan.sections.getValue("spine").first { plan.steps[it].after?.startsWith("minecraft:ladder[") == true }
            for (y in plan.bottom.y..plan.bottom.y + 2) {
                val point = CivsWorksiteLayouts.point(mine, plan.bottom.copy(y = y))
                val backing = CivsWorksiteLayouts.point(mine, plan.backing.copy(y = y))
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(rung, mine.rotation))
                world.getBlockAt(backing.x, backing.y, backing.z).type = Material.COBBLESTONE
            }
            val point = CivsWorksiteLayouts.point(mine, plan.steps.first().point)
            val target = world.getBlockAt(point.x, point.y, point.z)
            target.type = Material.WATER
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
                    fun advance(pulses: Int): CivWorldWork? {
                        repeat(pulses) {
                            world.time = 0
                            runtime.pulse(listOf(city))
                            runtime.plan(city, worker)?.let { return it }
                            paper.performTicks(5)
                        }
                        return null
                    }
                    advance(256) shouldBe null
                    runtime.idleReason(city, worker) shouldBe "worksite-liquid"
                    reads = 0
                    advance(512) shouldBe null
                    reads shouldBe 0
                    val hall = city.buildings.last()
                    city = city.copy(buildings = city.buildings.dropLast(1) + hall.copy(
                        bounds = CivBox(point.copy(y = 80), point.copy(x = point.x + 2, y = 90, z = point.z + 2))))
                    advance(256) shouldBe null
                    (reads > 0) shouldBe true
                    reads = 0
                    advance(256) shouldBe null
                    reads shouldBe 0
                    runtime.invalidate(worker.id)
                    advance(256) shouldBe null
                    (reads > 0) shouldBe true
                    reads = 0
                    advance(256) shouldBe null
                    reads shouldBe 0
                    target.type = Material.STONE
                    runtime.changed(world.uid, point)
                    val work = requireNotNull(advance(512)) { runtime.debug().toString() }
                    runtime.validate(city, work)
                    city.workers.single() shouldBe worker
                    city.minePlans shouldBe null
                    city.stock shouldBe Stock()
                }
            }
        }
    }
})
