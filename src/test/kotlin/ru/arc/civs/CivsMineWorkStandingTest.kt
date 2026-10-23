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

class CivsMineWorkStandingTest : FunSpec({
    test("mine standing space contains its shaft and galleries but never the reported outside cave or paid shell") {
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-96, 60, -51)) shouldBe false
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-96, 62, -49)) shouldBe false
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-93, 63, -47)) shouldBe true
        CivsMinePlan.workStandingAllowed(mine, CivPosition(-93, 61, -47)) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-95.5, 60.0, -50.5)) shouldBe false
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-92.5, 61.0, -46.5)) shouldBe true
        citizenMineWorkRouteAllowed(mine, CitizenPoint(-85.5, 68.0, -40.5)) shouldBe true
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

    test("the rotated mine seals the reported wall from inside its connected shaft instead of the cave stance") {
        withReportedMine { _, world, mine, city, worker, runtime ->
            val work = nextMineWork(runtime, city, worker)
            work.focus shouldBe CivPosition(-96, 62, -49)
            work.status shouldBe "securing-mine"
            work.miningStep shouldBe 18L
            CivsMinePlan.workStandingAllowed(mine, work.standing) shouldBe true
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
            CivsMinePlan.workStandingAllowed(mine, second.standing) shouldBe true
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
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("a missing transfer footing is paid from the old ladder at step 29 before sealing the cave and cutting stone") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-transfer-footing").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineTransferFooting")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val worker = CivsEquipment.collectSupplies(spatialWorker(mine, "IRON_PICKAXE"),
                        mapOf("COBBLESTONE" to 2L, "COAL" to 4L, "GRANITE" to 2L))
                    var city = spatialCity(world.uid, listOf(mine), worker).copy(center = CivPosition(-25, 64, -20),
                        minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, 29)))
                    for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                    for (step in geometry.steps.take(29)) {
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData =
                            Bukkit.createBlockData(step.after ?: "minecraft:air").also {
                                if (step.after != null) it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                            }
                    }
                    for (y in 57..65) world.getBlockAt(-93, y, -47).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                    world.getBlockAt(-93, 61, -46).type = Material.AIR // Current canonical step is already open.
                    // These five body/support columns reproduce the native probes. The
                    // future extension still contains stone, while the near bridge is open.
                    for ((feet, material) in listOf(
                        CivPosition(-97, 61, -48) to Material.AIR,
                        CivPosition(-97, 61, -47) to Material.STONE,
                        CivPosition(-96, 61, -47) to Material.STONE,
                        CivPosition(-94, 61, -47) to Material.AIR,
                        CivPosition(-97, 61, -49) to Material.AIR,
                    )) {
                        world.getBlockAt(feet.x, feet.y - 1, feet.z).type = Material.AIR
                        world.getBlockAt(feet.x, feet.y, feet.z).type = material
                        world.getBlockAt(feet.x, feet.y + 1, feet.z).type = Material.AIR
                    }
                    val footing = CivPosition(-94, 60, -47)
                    val wall = CivPosition(-97, 61, -49)
                    (CivsMinePlan.localPoint(mine, footing) in geometry.transferFloor) shouldBe true
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        CivsStorage(plugin) { emptyArray() }.use { storage ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                            val first = nextMineWork(runtime, city, city.workers.single())
                            first.focus shouldBe footing
                            first.status shouldBe "securing-mine"
                            first.miningStep shouldBe 29L
                            first.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                            first.outputs shouldBe emptyMap()
                            // A floor target at y=60 must approach the old, usable ladder,
                            // not the future ladder at (-96,61,-47), whose feet are stone.
                            first.standing.x shouldBe -93
                            first.standing.z shouldBe -47
                            world.getBlockAt(footing.x, footing.y, footing.z).type shouldBe Material.AIR
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe 34L
                            var next = first
                            var cut = false
                            for (action in 0 until 16) {
                                val currentWorker = city.workers.single()
                                val excavating = next.changes.any { cell ->
                                    Bukkit.createBlockData(cell.after).material.isAir &&
                                        !Bukkit.createBlockData(cell.before).material.isAir
                                }
                                CivsMinePlan.workStandingAllowed(mine, next.standing) shouldBe true
                                citizenStandable(world, next.standing) shouldBe true
                                runtime.validate(city, next)
                                if (excavating) {
                                    // The optional bridge cannot waive the required cave shell.
                                    world.getBlockAt(wall.x, wall.y, wall.z).type shouldBe Material.COBBLESTONE
                                    cut = true
                                    break
                                }
                                next.status shouldBe "securing-mine"
                                next.miningStep shouldBe 29L
                                next.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                                next.outputs shouldBe emptyMap()
                                val before = CivsEquipment.plainCount(currentWorker, "COBBLESTONE")
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val native = storage.prepareWork(city, mine.id, next.storageInputs(currentWorker), emptyMap(), next.changes)
                                val after = CivsRules.finishWorldWork(city, next, 100_000 + action * 3_000L)
                                    .copy(inventory = native.inventoryAfter)
                                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                                    worldChanges = native.worldChanges).validated()
                                storage.seal(receipt, keepViewersOpen = true)
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                city = receipt.after
                                storage.unlock(id)
                                CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe before - 1
                                city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe 29L
                                world.getBlockAt(footing.x, footing.y, footing.z).type shouldBe Material.COBBLESTONE
                                runtime.invalidate(currentWorker.id)
                                next = nextMineWork(runtime, city, city.workers.single())
                            }
                            cut shouldBe true
                            // The two obstructing future ladder cells were not cleared as a
                            // side effect of selecting or placing the prerequisite supports.
                            world.getBlockAt(-96, 61, -47).type shouldBe Material.STONE
                            world.getBlockAt(-97, 61, -47).type shouldBe Material.STONE
                        }
                    }
                } finally { tasks.close() }
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
                val city = spatialCity(world.uid, listOf(mine), worker).copy(center = mine.anchor,
                    minePlans = mapOf(mine.id.toString() to CivMinePlanState(CivsMinePlan.VERSION, 18)))
                for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                for (y in 57..65) world.getBlockAt(-93, y, -47).blockData =
                    Bukkit.createBlockData("minecraft:ladder[facing=south,waterlogged=false]")
                // A real two-high inner connection and the exposed outer shell face.
                for (x in -96..-94) for (z in -48..-47) for (y in 61..62)
                    world.getBlockAt(x, y, z).type = Material.AIR
                world.getBlockAt(-93, 61, -48).type = Material.AIR
                world.getBlockAt(-93, 62, -48).type = Material.AIR
                world.getBlockAt(-96, 62, -49).type = Material.AIR
                world.getBlockAt(-96, 63, -49).type = Material.GRANITE
                // The reported cave point is supported and clear but outside mine passage space.
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
