package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class CivsMineFrontierRepairTest : FunSpec({
    test("a new deep ladder seals its immediate passage instead of chasing a far cave boundary") {
        frontierRepairFixture { f ->
            val work = f.nextWork()
            work.status shouldBe "securing-mine"
            work.changes.single().before shouldBe "minecraft:air"
            work.changes.single().after shouldBe "minecraft:cobblestone"
            (work.changes.single().position != f.primary) shouldBe true
            val plan = requireNotNull(CivsMinePlan.forBuilding(f.mine, 6))
            val local = CivsMinePlan.localPoint(f.mine, work.changes.single().position)
            val ladder = plan.ladderAt(local.y)
            (kotlin.math.abs(local.x - ladder.x) + kotlin.math.abs(local.z - ladder.z)) shouldBe 1
            work.inputs shouldBe emptyMap()
            work.outputs shouldBe emptyMap()
            f.runtime.validate(f.city, work)
            val before = f.city.minePlans!!.getValue(f.mine.id.toString())
            val after = CivsRules.finishAutonomousVisual(f.city, work)
            f.runtime.applyAutonomousVisual(f.city, work)
            after.stock shouldBe f.city.stock
            after.workers shouldBe f.city.workers
            val saved = Gson().fromJson(Gson().toJson(after), Settlement::class.java)
            val proof = saved.minePlans!!.getValue(f.mine.id.toString())
            proof.nextStep shouldBe before.nextStep
            proof.sectionSteps shouldBe before.sectionSteps
            proof.v5Proof shouldBe before.v5Proof
            proof.seals!!.getValue(CivsMineProgress.key(CivsMinePlan.localPoint(f.mine, work.changes.single().position))) shouldBe "minecraft:cobblestone"
            f.world.getBlockAt(work.changes.single().position.x, work.changes.single().position.y,
                work.changes.single().position.z).type shouldBe Material.COBBLESTONE
            // Restart keeps the canonical proof; a new owner still rechecks the native frontier.
            val restarted = CivsWorksites(f.plugin, f.access, { _, _ -> emptyMap() }, { _, _ -> false }, nanoTime = { 0L })
            repeat(256) {
                restarted.pulse(listOf(saved))
                restarted.plan(saved, saved.workers.single())?.let { next ->
                    next.status shouldBe "securing-mine"
                    next.changes.single().after shouldBe "minecraft:cobblestone"
                }
                f.paper.performTicks(5)
            }
            f.world.getBlockAt(f.primary.x, f.primary.y, f.primary.z).type shouldBe Material.AIR
        }
    }

    test("only the new lower row narrows while the old boundary and completed spine retain the broad shell") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 80, 0), 180)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val oldLowest = requireNotNull(plan.forVersion(5)).lowest
        val count = plan.sections.getValue("spine").indexOfFirst { plan.steps[it].point.y < oldLowest }
        frontierRepairFixture(spineCount = count) { f ->
            f.nextWork()
            val narrow = f.frontierCells(plan, f.primaryLocal, f.city.minePlans!!.getValue(f.mine.id.toString()).sectionSteps!!)
            val lower = narrow.filter { it.first.y == oldLowest - 1 }
            val upper = narrow.filter { it.first.y == oldLowest }
            lower.isNotEmpty() shouldBe true
            lower.all { (exposed, _) -> exposed.x == plan.ladderAt(exposed.y).x && exposed.z == plan.ladderAt(exposed.y).z } shouldBe true
            (upper.map { it.first.x to it.first.z }.distinct().size > 1) shouldBe true
            val broad = f.frontierCells(plan, f.primaryLocal, mapOf("spine" to plan.sections.getValue("spine").size.toLong()))
            (broad.filter { it.first.y == oldLowest - 1 }.map { it.first.x to it.first.z }.distinct().size > 1) shouldBe true
            upper.toSet() shouldBe broad.filter { it.first.y == oldLowest }.toSet()
        }
    }

    test("completed-spine widening can reopen an exact own seal but cannot remove an unproven cobblestone block") {
        frontierRepairFixture { f ->
            val plan = requireNotNull(CivsMinePlan.forBuilding(f.mine, 6))
            val ladder = plan.ladderAt(f.primaryLocal.y)
            val local = listOf(ladder.copy(x = ladder.x + 1), ladder.copy(x = ladder.x - 1),
                ladder.copy(z = ladder.z + 1), ladder.copy(z = ladder.z - 1)).first { it in plan.plannedCuts }
            val ordinal = plan.cutOrdinals.getValue(local).first()
            val section = plan.section(ordinal)
            val offset = plan.sections.getValue(section).indexOf(ordinal).toLong()
            val (prefix, sections) = plan.normalizedProgress(0, mapOf("spine" to plan.sections.getValue("spine").size.toLong(), section to offset))
            val proof = f.city.minePlans!!.getValue(f.mine.id.toString()).copy(nextStep = prefix,
                sectionSteps = sections, seals = mapOf(CivsMineProgress.key(local) to "minecraft:cobblestone"))
            CivsMineProgress.valid(plan, proof, f.mine) shouldBe true
            val city = f.city.copy(minePlans = mapOf(f.mine.id.toString() to proof))
            for (x in ladder.x - 3..ladder.x + 3) for (z in ladder.z - 3..ladder.z + 3)
                for (y in local.y - 1..local.y + 2) {
                    val p = CivsWorksiteLayouts.point(f.mine, CivPosition(x, y, z))
                    f.world.getBlockAt(p.x, p.y, p.z).type = Material.STONE
                }
            for ((index, step) in plan.steps.withIndex()) if (plan.completed(index, prefix, sections)) {
                val p = CivsWorksiteLayouts.point(f.mine, step.point)
                f.world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, f.mine.rotation))
            }
            val point = CivsWorksiteLayouts.point(f.mine, local)
            f.world.getBlockAt(point.x, point.y, point.z).type = Material.COBBLESTONE
            val completed = plan.advanceSection(ordinal, sections)
            val after = plan.normalizedProgress(prefix, completed)
            val worker = city.workers.single()
            val work = CivWorldWork(worker.id, worker.completedCycles, f.mine.id,
                CivsWorksiteLayouts.point(f.mine, ladder.copy(y = ladder.y + 1)),
                listOf(CivPlanBlock(point, "minecraft:cobblestone", "minecraft:air")), status = "digging-mine",
                miningPlanVersion = 6, miningStep = after.first, miningAccessStep = prefix,
                miningSections = after.second, miningAccessSections = sections, miningOrdinal = ordinal, focus = point)
            CivsMineProgress.after(f.mine, proof, null, work)
            f.runtime.validateMineReceiptNative(city, work)
            shouldThrow<CivsRejection> { f.runtime.validateMineReceiptNative(city.copy(minePlans = mapOf(
                f.mine.id.toString() to proof.copy(seals = null))), work) }.reason shouldBe "mine-foreign-block"
            f.world.getBlockAt(point.x, point.y, point.z).type shouldBe Material.COBBLESTONE
        }
    }

    test("a fully unreachable frontier never authorizes the underlying cut or support placement") {
        frontierRepairFixture(missingTopRung = true) { f ->
            repeat(512) {
                f.runtime.pulse(listOf(f.city))
                f.runtime.plan(f.city, f.city.workers.single()) shouldBe null
                f.paper.performTicks(5)
            }
            f.world.getBlockAt(f.primary.x, f.primary.y, f.primary.z).type shouldBe Material.AIR
            f.city.workers.single().completedCycles shouldBe 0L
            f.city.minePlans!!.getValue(f.mine.id.toString()).sectionSteps shouldBe mapOf("spine" to 42L)
            val entry = (f.runtime.debug()["entries"] as List<*>).single() as Map<*, *>
            ((entry["need"] as Map<*, *>)["status"] == "searching-work") shouldBe false
        }
    }

    test("liquid and protection rejection still stop the frontier before any action") {
        for (liquid in listOf(true, false)) frontierRepairFixture(primaryLiquid = liquid, primaryLocked = !liquid) { f ->
            repeat(128) {
                f.runtime.pulse(listOf(f.city))
                f.runtime.plan(f.city, f.city.workers.single()) shouldBe null
                f.paper.performTicks(5)
            }
            val entry = (f.runtime.debug()["entries"] as List<*>).single() as Map<*, *>
            val need = entry["need"] as Map<*, *>
            need["status"] shouldBe if (liquid) "worksite-liquid" else "worksite-protected"
            f.world.getBlockAt(f.primary.x, f.primary.y, f.primary.z).type shouldBe if (liquid) Material.WATER else Material.AIR
        }
    }
})

private class FrontierRepairFixture(val paper: MockBukkitTestRuntime, val world: CivsWorksiteWorldFixture,
    val plugin: Plugin, val access: CivsWorld, val city: Settlement, val mine: CivBuilding,
    val primary: CivPosition, val primaryLocal: CivPosition, val runtime: CivsWorksites) {
    fun frontierCells(plan: CivsMinePlan, around: CivPosition, sections: Map<String, Long>): List<Pair<CivPosition, CivPosition>> {
        val method = CivsWorksites::class.java.declaredMethods.single { it.name == "mineFrontier" }.also { it.isAccessible = true }
        val tasks = method.invoke(runtime, city, mine, world, plan, around, emptySet<CivPosition>(), null,
            city.minePlans!!.getValue(mine.id.toString()).nextStep, emptySet<CivPosition>(), sections) as List<*>
        return tasks.map { raw ->
            val task = requireNotNull(raw)
            fun field(name: String) = task.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(task) as CivPosition
            field("exposed") to field("block")
        }
    }

    fun nextWork(): CivWorldWork {
        repeat(512) {
            runtime.pulse(listOf(city))
            runtime.plan(city, city.workers.single())?.let { return it }
            paper.performTicks(5)
        }
        error("No reachable repair: ${runtime.debug()}")
    }
}

/** A generated cave behind a paid backing; no real private terrain or resident data is embedded. */
private fun frontierRepairFixture(missingTopRung: Boolean = false, primaryLiquid: Boolean = false,
    primaryLocked: Boolean = false, spineCount: Int = 42, check: (FrontierRepairFixture) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("frontier-repair-order").also(paper.server::addWorld)
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 80, 0), 180)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val spine = plan.sections.getValue("spine")
        val (prefix, sections) = plan.normalizedProgress(0, mapOf("spine" to spineCount.toLong()))
        val before = CivMinePlanState(6, prefix, sectionSteps = sections,
            v5Step = 4, v5Proof = CivMineV5Proof(4, emptyMap(), null, null))
        CivsMineProgress.valid(plan, before, mine) shouldBe true
        val worker = spatialWorker(mine, "IRON_PICKAXE")
        val city = spatialCity(world.uid, listOf(mine), worker).copy(autonomy = CivAutonomy(),
            inventory = null, stock = Stock(20, 20, 1), minePlans = mapOf(mine.id.toString() to before))
        for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
        seedMineNaturalCover(world, mine)
        for (index in spine.take(spineCount)) {
            val point = CivsWorksiteLayouts.point(mine, plan.steps[index].point)
            world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(plan.expectedAfter(index, mine.rotation))
        }
        val ladderData = plan.expectedAfter(spine.first { plan.steps[it].after?.startsWith("minecraft:ladder[") == true }, mine.rotation)
        for (y in plan.bottom.y..plan.bottom.y + 2) {
            val rung = CivsWorksiteLayouts.point(mine, plan.bottom.copy(y = y))
            val support = CivsWorksiteLayouts.point(mine, plan.backing.copy(y = y))
            world.getBlockAt(rung.x, rung.y, rung.z).blockData = Bukkit.createBlockData(ladderData)
            world.getBlockAt(support.x, support.y, support.z).type = Material.COBBLESTONE
        }
        val primaryLocal = plan.steps[spine[spineCount]].point
        val rung = plan.ladderAt(primaryLocal.y)
        val component = plan.shaftColumns.filter { (x, z) -> kotlin.math.abs(x - rung.x) <= 1 && kotlin.math.abs(z - rung.z) <= 1 }.toSet()
        val boundary = component.flatMap { (x, z) -> listOf(x + 1 to z, x - 1 to z, x to z + 1, x to z - 1) }.toSet() - plan.shaftColumns
        for ((x, z) in component + boundary) {
            val point = CivsWorksiteLayouts.point(mine, CivPosition(x, primaryLocal.y, z))
            world.getBlockAt(point.x, point.y, point.z).type = Material.AIR
        }
        for ((x, z) in component) {
            val point = CivsWorksiteLayouts.point(mine, CivPosition(x, primaryLocal.y + 1, z))
            val block = world.getBlockAt(point.x, point.y, point.z)
            if (block.type != Material.LADDER && block.type != Material.COBBLESTONE) block.type = Material.AIR
        }
        val primary = CivsWorksiteLayouts.point(mine, primaryLocal)
        if (primaryLiquid) world.getBlockAt(primary.x, primary.y, primary.z).type = Material.WATER
        if (missingTopRung) {
            val top = CivsWorksiteLayouts.point(mine, plan.bottom)
            world.getBlockAt(top.x, top.y, top.z).type = Material.AIR
        }
        val plugin = paper.createSimplePlugin("FrontierRepairOrder")
        LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() },
                    { _, p -> primaryLocked && p == primary }, nanoTime = { 0L })
                check(FrontierRepairFixture(paper, world, plugin, access, city, mine, primary, primaryLocal, runtime))
            }
        }
    }
}
