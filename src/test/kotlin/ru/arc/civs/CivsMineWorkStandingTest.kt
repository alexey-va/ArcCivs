package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.structure.StructureRotation
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.Executor

class CivsMineWorkStandingTest : FunSpec({
    test("mine standing space contains its shaft and galleries but never the reported outside cave or paid shell") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-96, 60, -51)) shouldBe false
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-96, 62, -49)) shouldBe false
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-93, 63, -47)) shouldBe true
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-93, 61, -47)) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-95.5, 60.0, -50.5), plan.steps.size.toLong()) shouldBe false
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-92.5, 61.0, -46.5), plan.steps.size.toLong()) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-85.5, 68.0, -40.5), 0) shouldBe true
        plan.containsWorkStanding(plan.backing) shouldBe false
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = mine.copy(rotation = rotation)
            val support = plan.floors.first().cells.first { (it.x to it.z) !in plan.shaftColumns }
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, support)) shouldBe false
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, support.copy(y = support.y + 1))) shouldBe true
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, support.copy(y = support.y + 3))) shouldBe false
            CivsMinePlan.workStandingAllowed(rotated, CivsWorksiteLayouts.point(rotated, requireNotNull(plan.generatorStanding))) shouldBe true
        }
    }

    test("the rotated current entrance pays its missing footing from a completed inside stance") {
        withReportedMine { _, world, mine, city, worker, runtime ->
            val work = nextMineWork(runtime, city, worker)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            val firstFloor = geometry.steps.first { it.after == "minecraft:cobblestone" && !it.shaftSupport }
            work.focus shouldBe CivsWorksiteLayouts.point(mine, firstFloor.point)
            work.status shouldBe "digging-mine"
            work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            work.miningStep shouldBe city.minePlans!!.getValue(mine.id.toString()).nextStep + 1
            geometry.builtStanding(CivsMinePlan.localPoint(mine, work.standing),
                requireNotNull(work.miningAccessStep)) shouldBe true
            (work.standing != CivPosition(-96, 60, -51)) shouldBe true
            citizenStandable(world, work.standing) shouldBe true
            runtime.validate(city, work)
            // Selecting a stance cannot repair terrain or spend the worker's supplies.
            world.getBlockAt(-96, 62, -49).type shouldBe Material.AIR
            CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
        }
    }

    test("a failed inside stance selects another for the same action and expires without spending inputs") {
        withReportedMine { advanceTicks, _, mine, city, worker, runtime ->
            val first = nextMineWork(runtime, city, worker)
            runtime.rejectStanding(city, worker, first) shouldBe true
            runtime.rejectStanding(city, worker, first) shouldBe false
            runtime.invalidate(worker.id) // Ordinary cache invalidation must preserve the failed stance.
            val second = nextMineWork(runtime, city, worker)
            (second.standing != first.standing) shouldBe true
            second.focus shouldBe first.focus
            second.miningStep shouldBe first.miningStep
            second.changes shouldBe first.changes
            second.inputs shouldBe first.inputs
            requireNotNull(CivsMinePlan.forBuilding(mine)).builtStanding(CivsMinePlan.localPoint(mine, second.standing),
                requireNotNull(second.miningAccessStep)) shouldBe true
            runtime.validate(city, second)
            advanceTicks(1_201)
            runtime.invalidate(worker.id)
            nextMineWork(runtime, city, worker).standing shouldBe first.standing
            CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
        }
    }

    test("completed gallery lighting can use another inside stance without leaving its passages") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-generator-standing").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineGeneratorStanding")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 68, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans =
                        mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, geometry.steps.size.toLong())))
                    for (x in -3..3) for (z in -3..3) world.loadChunk(x, z)
                    for (step in geometry.steps) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air")
                    }
                    val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                    for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                    val generatorStanding = requireNotNull(geometry.generatorStanding)
                    val light = CivsWorksiteLayouts.point(mine, geometry.floors.last().torches.first {
                        kotlin.math.abs(it.x - generatorStanding.x) + kotlin.math.abs(it.z - generatorStanding.z) == 1
                    })
                    // This real paid lighting action precedes the generator's random output roll.
                    world.getBlockAt(light.x, light.y, light.z).type = Material.AIR
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        val first = nextMineWork(runtime, city, worker)
                        first.miningGenerator shouldBe false
                        first.status shouldBe "securing-mine"
                        first.focus shouldBe light
                        first.inputs shouldBe mapOf("TORCH" to 1L)
                        CivsMinePlan.workStandingAllowed(mine, first.standing) shouldBe true
                        runtime.validate(city, first)
                        runtime.rejectStanding(city, worker, first) shouldBe true
                        val second = nextMineWork(runtime, city, worker)
                        second.miningGenerator shouldBe false
                        second.focus shouldBe first.focus
                        second.inputs shouldBe first.inputs
                        (second.standing != first.standing) shouldBe true
                        CivsMinePlan.workStandingAllowed(mine, second.standing) shouldBe true
                        runtime.validate(city, second)
                        world.getBlockAt(light.x, light.y, light.z).type shouldBe Material.AIR
                        CivsEquipment.plainCount(worker, "TORCH") shouldBe 8L
                        // A neighbouring safety target must not erase an unreachable stance
                        // for the same worker/cycle/step. Reproduce the reported A→B→A loop.
                        val otherLight = CivsWorksiteLayouts.point(mine, geometry.floors.last().torches.first {
                            it != CivsMinePlan.localPoint(mine, light) &&
                                kotlin.math.abs(it.x - generatorStanding.x) + kotlin.math.abs(it.z - generatorStanding.z) == 1
                        })
                        world.getBlockAt(light.x, light.y, light.z).type = Material.TORCH
                        world.getBlockAt(otherLight.x, otherLight.y, otherLight.z).type = Material.AIR
                        runtime.invalidate(worker.id)
                        val neighbour = nextMineWork(runtime, city, worker)
                        neighbour.focus shouldBe otherLight
                        (neighbour.standing != first.standing) shouldBe true
                        world.getBlockAt(otherLight.x, otherLight.y, otherLight.z).type = Material.TORCH
                        world.getBlockAt(light.x, light.y, light.z).type = Material.AIR
                        runtime.invalidate(worker.id)
                        val returned = nextMineWork(runtime, city, worker)
                        returned.focus shouldBe first.focus
                        (returned.standing != first.standing) shouldBe true
                        returned.inputs shouldBe first.inputs
                        CivsEquipment.plainCount(worker, "TORCH") shouldBe 8L
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("reported V3 step83 cannot choose the future lower cave stance while its ladder is unfinished") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("reported-v3-step83").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("ReportedStep83")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                    val old = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
                    val current = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = spatialWorker(mine, "IRON_PICKAXE")
                    var city = spatialCity(world.uid, listOf(mine), worker).copy(center = CivPosition(-25, 64, -20),
                        minePlans = mapOf(mine.id.toString() to CivMinePlanState(3, 83)))
                    for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                    val top = CivsWorksiteLayouts.point(mine, old.bottom)
                    for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                    for (step in old.steps.take(83)) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData =
                            Bukkit.createBlockData(step.after ?: "minecraft:air").also {
                                if (step.after != null) it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                            }
                    }
                    val reported = CivPosition(-96, 55, -47)
                    world.getBlockAt(reported.x, reported.y - 1, reported.z).type = Material.COBBLESTONE
                    for (y in reported.y..reported.y + 1) world.getBlockAt(reported.x, y, reported.z).type = Material.AIR
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        var runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        val work = nextMineWork(runtime, city, worker)
                        work.miningPlanVersion shouldBe CivsMinePlan.VERSION
                        (work.standing != reported) shouldBe true
                        current.builtStanding(CivsMinePlan.localPoint(mine, work.standing),
                            requireNotNull(work.miningAccessStep)) shouldBe true
                        for (y in work.standing.y..top.y) world.getBlockAt(top.x, y, top.z).type shouldBe Material.LADDER
                        runtime.validate(city, work)
                        city.minePlans!!.getValue(mine.id.toString()) shouldBe CivMinePlanState(3, 83)
                        CivsEquipment.plainCount(worker, "COBBLESTONE") shouldBe 32L
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            var removedLining = false
                            var restarted = false
                            val paidLining = hashSetOf<CivPosition>()
                            val directory = Files.createTempDirectory("mine-v4-paid-lining-restart")
                            CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                            repeat(40) { action ->
                                val currentWorker = city.workers.single()
                                val next = nextMineWork(runtime, city, currentWorker)
                                runtime.validate(city, next)
                                val change = next.changes.single()
                                if (next.status == "securing-mine" && change.after == "minecraft:cobblestone")
                                    paidLining += change.position
                                if (change.position in paidLining && change.before == "minecraft:cobblestone" &&
                                    change.after == "minecraft:air") {
                                    next.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                                    removedLining = true
                                }
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val native = storage.prepareWork(city, mine.id, next.storageInputs(currentWorker), emptyMap(), next.changes)
                                val after = CivsRules.finishWorldWork(city, next, 100_000 + action * 5_000L)
                                    .copy(inventory = native.inventoryAfter)
                                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                                    worldChanges = native.worldChanges).validated()
                                storage.seal(receipt, keepViewersOpen = true)
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.unlock(id)
                                city = receipt.after
                                CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                                if (!restarted && paidLining.isNotEmpty() && !removedLining) {
                                    restarted = true
                                    city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
                                    runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                                }
                                city.minePlans!!.getValue(mine.id.toString()).v3Step shouldBe 83L
                                runtime.invalidate(currentWorker.id)
                            }
                            restarted shouldBe true
                            removedLining shouldBe true
                            (city.minePlans!!.getValue(mine.id.toString()).nextStep > 22) shouldBe true
                        }
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("the V4 entrance floor uses a durable paid native receipt before excavation continues") {
        withReportedMine { _, world, mine, original, worker, runtime ->
            val work = nextMineWork(runtime, original, worker)
            val footing = requireNotNull(work.focus)
            work.inputs shouldBe mapOf("COBBLESTONE" to 1L)
            work.outputs shouldBe emptyMap()
            runtime.validate(original, work)
            val plugin = Bukkit.getPluginManager().getPlugin("MineWorkStanding")!!
            CivsStorage(plugin) { emptyArray() }.use { storage ->
                val id = UUID.randomUUID()
                storage.reserve(original, id)
                val native = storage.prepareWork(original, mine.id, work.storageInputs(worker), emptyMap(), work.changes)
                val after = CivsRules.finishWorldWork(original, work, 100_000).copy(inventory = native.inventoryAfter)
                val receipt = CivInventoryTransaction(id, original.id, original.revision, after, native.changes,
                    worldChanges = native.worldChanges).validated()
                storage.seal(receipt, keepViewersOpen = true)
                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(id)
                world.getBlockAt(footing.x, footing.y, footing.z).type shouldBe Material.COBBLESTONE
                CivsEquipment.plainCount(receipt.after.workers.single(), "COBBLESTONE") shouldBe
                    CivsEquipment.plainCount(worker, "COBBLESTONE") - 1
                receipt.after.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe work.miningStep
                runtime.invalidate(worker.id)
                val next = nextMineWork(runtime, receipt.after, receipt.after.workers.single())
                citizenStandable(world, next.standing) shouldBe true
                runtime.validate(receipt.after, next)
            }
        }
    }

})

private fun nextMineWork(runtime: CivsWorksites, city: Settlement, worker: CivWorker): CivWorldWork {
    repeat(64) { runtime.pulse(listOf(city)) }
    return withClue("idle=${runtime.idleReason(city, worker)}") { requireNotNull(runtime.plan(city, worker)) }
}

private fun withReportedMine(
    action: ((Int) -> Unit, CivsWorksiteWorldFixture, CivBuilding, Settlement, CivWorker, CivsWorksites) -> Unit,
) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-work-standing").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineWorkStanding")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                val worker = spatialWorker(mine, "IRON_PICKAXE")
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                val prefix = geometry.steps.indexOfFirst { it.after == "minecraft:cobblestone" && !it.shaftSupport }
                val city = spatialCity(world.uid, listOf(mine), worker).copy(center = CivPosition(-25, 64, -20),
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, prefix.toLong())))
                for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                for (y in top.y..top.y + 3) world.getBlockAt(top.x, y, top.z).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                for (step in geometry.steps.take(prefix)) {
                    val p = CivsWorksiteLayouts.point(mine, step.point)
                    world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air").also {
                        if (step.after != null) it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                    }
                }
                val support = CivsWorksiteLayouts.point(mine, geometry.steps[prefix].point)
                world.getBlockAt(support.x, support.y, support.z).type = Material.AIR
                // These real native cave cells are not proof of a built lower route.
                world.getBlockAt(-96, 62, -49).type = Material.AIR
                world.getBlockAt(-96, 59, -51).type = Material.GRASS_BLOCK
                for (y in 60..61) world.getBlockAt(-96, y, -51).type = Material.AIR
                CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                    val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    action({ paper.performTicks(it.toLong()) }, world, mine, city, worker, runtime)
                }
            } finally { tasks.close() }
        }
    }
}
