package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

class CivsMineSearchWarningsTest : FunSpec({
    test("a slow native V4 migration scan warns once per active minute then reports actual selected work without work credit") {
        withSlowMineSearch { paper, world, mine, city, runtime, records, _ ->
            val worker = city.workers.single()
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val committed = city.minePlans!!.getValue(mine.id.toString()).nextStep
            val outstanding = CivsWorksiteLayouts.point(mine, geometry.steps.last().point)
            fun pulse(ticks: Int = 20) {
                paper.performTicks(ticks.toLong())
                val rays = world.rayTraces
                runtime.pulse(listOf(city))
                (world.rayTraces - rays <= 8) shouldBe true
            }
            fun warnings() = records.filter { "event=mine_search_slow " in it.message }
            pulse(0)
            repeat(59) { pulse() }
            warnings() shouldBe emptyList()
            pulse()
            val first = warnings().single()
            first.level shouldBe Level.WARNING
            for (field in listOf("city=${city.id}", "worker=${worker.id}", "building=${mine.id}",
                "cycle=0", "mine_version=4", "search_version=${CivsMinePlan.VERSION}", "committed_step=$committed", "active_ticks=1200", "active_ms=60000",
                "active_time_source=server-ticks", "frontier_target=", "frontier_excavation=-",
                "frontier_next=", "frontier_deferred=", "candidate=", "aim=", "outcome=searching-work"))
                withClue(first.message) { (field in first.message) shouldBe true }
            val scan = ((runtime.debug(setOf(worker.id))["mineCursors"] as Map<*, *>)["entries"] as List<*>)
                .single() as Map<*, *>
            ((scan["scanStep"] as Number).toLong() >= 0) shouldBe true
            ("scan_step=-" in first.message) shouldBe false
            repeat(8) { pulse(0) }
            repeat(59) { pulse() }
            warnings().size shouldBe 1
            pulse()
            warnings().size shouldBe 2
            ("active_ticks=2400" in warnings().last().message) shouldBe true
            runtime.plan(city, worker) shouldBe null
            city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe committed
            city.workers.single().completedCycles shouldBe 0L
            world.getBlockAt(outstanding.x, outstanding.y, outstanding.z).type shouldBe Material.STONE

            var selected: CivWorldWork? = null
            for (attempt in 0 until 10_000) {
                pulse(0)
                selected = runtime.plan(city, worker)
                if (selected != null) break
            }
            val work = withClue("idle=${runtime.idleReason(city, worker)}") { requireNotNull(selected) }
            runtime.validate(city, work)
            val completed = records.single { "event=mine_search_work_selected " in it.message }
            completed.level shouldBe Level.INFO
            ("outcome=work_selected" in completed.message) shouldBe true
            ("selected_step=${work.miningStep}" in completed.message) shouldBe true
            ("selected_focus=\"${citizenWorkFocus(work)}\"" in completed.message) shouldBe true
            ("selected_standing=\"${work.standing}\"" in completed.message) shouldBe true
            mineSearchTicks(runtime, worker.id) shouldBe null
            repeat(8) { pulse(0) }
            records.count { "event=mine_search_work_selected " in it.message } shouldBe 1
            warnings().size shouldBe 2
            city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe committed
            city.workers.single() shouldBe worker
            world.getBlockAt(outstanding.x, outstanding.y, outstanding.z).type shouldBe Material.STONE
        }
    }

    test("mine search time excludes pause night material and tool waits and resets on cycle job and worker removal") {
        withSlowMineSearch { paper, world, mine, original, runtime, records, paused ->
            var city = original
            val worker = city.workers.single()
            fun pulse(ticks: Int = 20) {
                paper.performTicks(ticks.toLong())
                runtime.pulse(listOf(city))
            }
            pulse(0)
            repeat(59) { pulse() }
            mineSearchTicks(runtime, worker.id) shouldBe 1_180L
            paused(true)
            pulse(2_400)
            paused(false)
            pulse(0)
            world.time = 13_000
            pulse(2_400)
            world.time = 0
            pulse(0)
            val noStone = worker.copy(items = CivsEquipment.slots(worker).map {
                it?.takeUnless { item -> item.material == "COBBLESTONE" }
            })
            city = city.copy(workers = listOf(noStone))
            pulse(2_400)
            runtime.idleReason(city, noStone) shouldBe "waiting-cobblestone"
            val noTool = worker.copy(items = CivsEquipment.slots(worker).toMutableList().also { it[4] = null })
            city = city.copy(workers = listOf(noTool))
            pulse(2_400)
            runtime.idleReason(city, noTool) shouldBe "waiting-tool"
            mineSearchTicks(runtime, worker.id) shouldBe 1_180L
            records.none { "event=mine_search_slow " in it.message } shouldBe true
            city = original
            pulse(0)
            pulse()
            records.count { "event=mine_search_slow " in it.message } shouldBe 1

            city = city.copy(workers = listOf(worker.copy(completedCycles = 1)))
            world.time = 0
            pulse(2_400)
            mineSearchTicks(runtime, worker.id) shouldBe 0L
            val reassigned = mine.copy(id = UUID.randomUUID())
            city = city.copy(buildings = city.buildings.map { if (it.id == mine.id) reassigned else it },
                workers = city.workers.map { it.copy(assignment = reassigned.id) },
                minePlans = mapOf(reassigned.id.toString() to CivMinePlanState(4, 0)))
            world.time = 0
            pulse(2_400)
            mineSearchTicks(runtime, worker.id) shouldBe 0L
            runtime.pulse(emptyList())
            mineSearchTicks(runtime, worker.id) shouldBe null
            world.time = 0
            pulse(2_400)
            mineSearchTicks(runtime, worker.id) shouldBe 0L
            records.count { "event=mine_search_slow " in it.message } shouldBe 1
            records.none { "event=mine_search_work_selected " in it.message } shouldBe true
        }
    }
})

private fun mineSearchTicks(runtime: CivsWorksites, worker: UUID): Long? {
    val entry = (runtime.debug(setOf(worker))["entries"] as List<*>).singleOrNull() as? Map<*, *> ?: return null
    return ((entry["mineSearch"] as? Map<*, *>)?.get("activeTicks") as? Number)?.toLong()
}

private fun withSlowMineSearch(action: (MockBukkitTestRuntime, CivsWorksiteWorldFixture, CivBuilding,
    Settlement, CivsWorksites, List<LogRecord>, (Boolean) -> Unit) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("mine-slow-search").also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("MineSlowSearch")
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
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine, 4))
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                minePlans = mapOf(mine.id.toString() to CivMinePlanState(4, geometry.shaftEnd.toLong())))
            for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
            // A real completed V4 prefix migrates through the bounded native V5 frontier scan;
            // the durable cursor and worker receive no work credit while it catches up.
            for (step in geometry.steps.dropLast(1)) {
                val point = CivsWorksiteLayouts.point(mine, step.point)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
            }
            val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
            for (dy in 0..5) {
                world.getBlockAt(top.x, top.y + dy, top.z).blockData = Bukkit.createBlockData(when {
                    dy <= 2 -> "minecraft:ladder[facing=${geometry.facing},waterlogged=false]"
                    dy < 5 -> "minecraft:air"
                    else -> "minecraft:dirt"
                })
                if (dy < 5) {
                    val backing = CivsWorksiteLayouts.point(mine, geometry.backing)
                    world.getBlockAt(backing.x, top.y + dy, backing.z).type = Material.COBBLESTONE
                }
            }
            var paused = false
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false }, { paused })
                action(paper, world, mine, city, runtime, records) { paused = it }
            }
        } finally {
            plugin.logger.removeHandler(handler)
            tasks.close()
        }
    }
}
