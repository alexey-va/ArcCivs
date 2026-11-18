package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMinePlanTest : FunSpec({
    test("V2 keeps the legacy torches and recognizes the expanded nine-column shaft") {
        for (style in listOf("nordic", "medievaloak", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(20, 80, -20))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 2))
            val legacySize = 12 * level + 219 * level
            val legacy = requireNotNull(plan.legacyCompleted(legacySize.toLong()))
            legacy.filterValues { it == "minecraft:torch" }.keys shouldBe plan.floors.flatMap { it.torches }.toSet()
            val newExcavation = plan.steps.filter { it.after == null }.map { it.point }.toSet() - legacy.keys
            newExcavation.size shouldBe listOf(21, 35, 49)[level - 1]
            plan.shaftColumns.size shouldBe 9
            for ((x, z) in plan.shaftColumns) {
                plan.containsShaft(CivPosition(x, plan.lowest, z)) shouldBe true
                plan.containsShaft(CivPosition(x, plan.bottom.y, z)) shouldBe true
            }
            plan.containsShaft(plan.bottom.copy(y = plan.lowest - 1)) shouldBe false
        }
    }

    test("mine local and world coordinates agree at all four rotations") {
        val building = spatialBuilding("nordic", "miner", 1, CivPosition(20, 80, -20))
        val local = CivPosition(2, -3, 5)
        for ((rotation, expected) in listOf(
            0 to CivPosition(22, 77, -15), 90 to CivPosition(15, 77, -18),
            180 to CivPosition(18, 77, -25), 270 to CivPosition(25, 77, -22),
        )) {
            val rotated = building.copy(rotation = rotation)
            CivsWorksiteLayouts.point(rotated, local) shouldBe expected
            CivsMinePlan.localPoint(rotated, expected) shouldBe local
        }
    }

    for (missingBacking in listOf(false, true)) test("central shaft refuses water and inaccessible lava with missing backing $missingBacking") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-inner-liquid-$missingBacking").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineInnerLiquid")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                    val bottom = CivsWorksiteLayouts.point(mine, plan.bottom)
                    val target = bottom.copy(y = bottom.y - 1)
                    val backing = CivsWorksiteLayouts.point(mine, plan.backing.copy(y = plan.bottom.y - 1))
                    val liquid = target.copy(z = target.z + 1)
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${plan.facing},waterlogged=false]")
                    if (missingBacking) world.getBlockAt(backing.x, backing.y, backing.z).type = Material.AIR
                    world.getBlockAt(liquid.x, liquid.y, liquid.z).type = Material.WATER
                    CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                        val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                        repeat(16) { runtime.pulse(listOf(city)) }
                        runtime.plan(city, city.workers.single()) shouldBe null
                        runtime.idleReason(city, city.workers.single()) shouldBe "worksite-liquid"
                        city.minePlans shouldBe null
                        city.workers.single().completedCycles shouldBe 0L
                        world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
                        // Lava clearance is authorized, but this source has no reachable native face.
                        world.getBlockAt(liquid.x, liquid.y, liquid.z).type = Material.LAVA
                        runtime.changed(world.uid, liquid)
                        runtime.invalidate(city.workers.single().id)
                        repeat(256) { runtime.pulse(listOf(city)) }
                        runtime.plan(city, city.workers.single()) shouldBe null
                        runtime.idleReason(city, city.workers.single()) shouldBe "worksite-no-access"
                        world.getBlockAt(liquid.x, liquid.y, liquid.z).type shouldBe Material.LAVA
                        world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
                        world.getBlockAt(liquid.x, liquid.y, liquid.z).type = Material.STONE
                        runtime.changed(world.uid, liquid)
                        repeat(32) { runtime.pulse(listOf(city)) }
                        val work = requireNotNull(runtime.plan(city, city.workers.single()))
                        work.changes.single().position shouldBe target
                        work.changes.single().after shouldBe "minecraft:air"
                        work.inputs shouldBe emptyMap()
                        runtime.validate(city, work)
                        world.getBlockAt(liquid.x, liquid.y, liquid.z).type = Material.LAVA
                        shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
                        world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
                    }
                } finally { tasks.close() }
            }
        }
    }

    test("a frontier wall becoming lava during mining rejects the receipt then uses a paid repair before excavation") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-final-frontier").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineFinalFrontier")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val stepIndex = geometry.steps.indexOfFirst { it.after == null &&
                        it.point == geometry.bottom.copy(y = geometry.bottom.y - 2, z = geometry.bottom.z + 1) }
                    val target = CivsWorksiteLayouts.point(mine, geometry.steps[stepIndex].point)
                    val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
                    // The preceding row is a critical face with a visible side through
                    // the upper lip; the solid roof hides the lower wall's placement face.
                    val wall = target.copy(y = target.y + 1, z = target.z + 1)
                    val key = mine.id.toString()
                    var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                        .copy(minePlans = mapOf(key to CivMinePlanState(CivsMinePlan.VERSION, stepIndex.toLong())))
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    seedMineNaturalCover(world, mine)
                    geometry.steps.take(stepIndex).forEachIndexed { index, step ->
                        val point = CivsWorksiteLayouts.point(mine, step.point)
                        world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(geometry.expectedAfter(index, mine.rotation))
                    }
                    city = city.copy(minePlans = mapOf(key to
                        seedMinePaidSpine(world, mine, city.minePlans!!.getValue(key))))
                    val initialState = city.minePlans
                    for (y in target.y - 1..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                    // Open the upper lip so the real outer wall's upper face is reachable
                    // for a paid placement without first breaking the unsafe target.
                    for (y in target.y + 1..65) world.getBlockAt(target.x, y, target.z).type = Material.AIR
                    val originalSupplies = CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE")
                    CivsStorage(plugin) { emptyArray() }.use { storage ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            // Geometry and receipt semantics use a deterministic clock; time budgets have dedicated tests.
                            val runtime = CivsWorksites(plugin, access, { current, id -> storage.buildingAvailable(current, id) },
                                { _, _ -> false }, nanoTime = { 0L })
                            fun nextWork(): CivWorldWork {
                                repeat(64) {
                                    runtime.pulse(listOf(city))
                                    runtime.plan(city, city.workers.single())?.let { return it }
                                }
                                error("frontier work blocked: ${runtime.idleReason(city, city.workers.single())}")
                            }
                            fun commit(work: CivWorldWork) {
                                runtime.validate(city, work)
                                val id = UUID.randomUUID()
                                storage.reserve(city, id)
                                val plan = storage.prepareWork(city, mine.id, work.storageInputs(city.workers.single()), emptyMap(), work.changes)
                                val after = CivsRules.finishWorldWork(city, work, 20_000 + work.cycle * 5_000).copy(inventory = plan.inventoryAfter)
                                val receipt = CivInventoryTransaction(id, city.id, city.revision, after, plan.changes, worldChanges = plan.worldChanges).validated()
                                storage.seal(receipt, keepViewersOpen = true)
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                                city = receipt.after
                                storage.unlock(id)
                                runtime.invalidate(work.workerId)
                            }
                            val excavation = nextWork()
                            excavation.changes.single().position shouldBe target
                            excavation.changes.single().after shouldBe "minecraft:air"
                            runtime.validate(city, excavation)
                            world.getBlockAt(wall.x, wall.y, wall.z).type = Material.LAVA
                            shouldThrow<CivsRejection> { runtime.validate(city, excavation) }.reason shouldBe "stale-worksite"
                            city.minePlans shouldBe initialState
                            city.workers.single().completedCycles shouldBe 0L
                            city.inventory!!.buildingStock(mine.id) shouldBe emptyMap()
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe originalSupplies
                            world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.STONE
                            runtime.invalidate(excavation.workerId)
                            val repair = nextWork()
                            repair.changes.single().position shouldBe wall
                            repair.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                            repair.outputs shouldBe emptyMap()
                            repair.status shouldBe "securing-mine"
                            repair.durationMillis shouldBe 2_000L
                            commit(repair)
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe originalSupplies - 1
                            val resumed = nextWork()
                            resumed.changes.single().position shouldBe target
                            resumed.outputs shouldBe mapOf("COBBLESTONE" to 1L)
                            commit(resumed)
                            city.inventory!!.buildingStock(mine.id) shouldBe emptyMap()
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe originalSupplies
                            city.workers.single().completedCycles shouldBe 2L
                        }
                    }
                } finally { tasks.close() }
            }
        }
    }

    for (legacyStep in listOf(147L, 180L)) test("legacy mine at $legacyStep cuts over only with its first physical receipt and resumes after restart") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
                val world = CivsWorksiteWorldFixture("mine-cutover-$legacyStep").also(paper.server::addWorld)
                val plugin = paper.createSimplePlugin("MineCutover")
                val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                val directory = Files.createTempDirectory("arccivs-mine-cutover-")
                try {
                    val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
                    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                    val key = mine.id.toString()
                    var city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE"))
                        .copy(mineProgress = mapOf(key to legacyStep))
                    for (x in -3..3) for (z in -3..2) world.loadChunk(x, z)
                    val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
                    for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
                        Bukkit.createBlockData("minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
                    val legacy = requireNotNull(geometry.legacyCompleted(legacyStep))
                    legacy.forEach { (local, expected) ->
                        val p = CivsWorksiteLayouts.point(mine, local)
                        world.getBlockAt(p.x, p.y, p.z).blockData = Bukkit.createBlockData(expected ?: "minecraft:air")
                    }
                    val oldPaid = legacy.filterValues { it == "minecraft:torch" || it == "minecraft:cobblestone" || it?.startsWith("minecraft:ladder") == true }
                        .mapKeys { CivsWorksiteLayouts.point(mine, it.key) }
                    CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                    CivsStorage(plugin) { emptyArray() }.use { storage ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            // Receipt/cutover geometry uses fixed time; the count quotas remain active.
                            // CivsMineSearchWarningsTest owns elapsed-budget yield/resume coverage.
                            var runtime = CivsWorksites(plugin, access, { current, id -> storage.buildingAvailable(current, id) }, { _, _ -> false },
                                nanoTime = { 0L })
                            fun nextWork(): CivWorldWork {
                                repeat(256) {
                                    runtime.pulse(listOf(city))
                                    runtime.plan(city, city.workers.single())?.let { return it }
                                }
                                error("cutover $legacyStep failed: ${runtime.idleReason(city, city.workers.single())}; ${runtime.debug()}")
                            }
                            val first = nextWork()
                            first.miningPlanVersion shouldBe CivsMinePlan.VERSION
                            first.durationMillis shouldBe 2_000L
                            first.changes.size shouldBe 1
                            first.changes.single().position.let { (it in oldPaid) shouldBe false }
                            city.minePlans shouldBe null
                            city.mineProgress shouldBe mapOf(key to legacyStep)
                            runtime.validate(city, first)
                            val id = UUID.randomUUID()
                            storage.reserve(city, id)
                            val prepared = storage.prepareWork(city, mine.id, first.storageInputs(city.workers.single()), emptyMap(), first.changes)
                            val after = CivsRules.finishWorldWork(city, first, 20_000).copy(inventory = prepared.inventoryAfter)
                            val receipt = CivInventoryTransaction(id, city.id, city.revision, after, prepared.changes,
                                worldChanges = prepared.worldChanges).validated()
                            // Old crash image: the before-city and one native receipt containing the new cursor.
                            writeLegacyInventoryReceipt(directory, receipt)
                            val reopened = CivsStore(directory, Executor { it.run() })
                            reopened.load().join().single().minePlans shouldBe null
                            val recovered = reopened.loadInventoryTransactions().join().single()
                            val recoveredState = recovered.after.minePlans!!.getValue(key)
                            recoveredState.version shouldBe CivsMinePlan.VERSION
                            recoveredState.nextStep shouldBe first.miningStep
                            recoveredState.legacyStep shouldBe legacyStep
                            recoveredState.sectionSteps shouldBe first.miningSections
                            recoveredState.excavation shouldBe CivsMinePlan.localPoint(mine, first.changes.single().position)
                            CivsMineProgress.valid(geometry, recoveredState, mine) shouldBe true
                            first.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString shouldBe cell.before }
                            storage.seal(recovered, keepViewersOpen = true)
                            storage.reconcile(recovered) shouldBe CivInventoryApplyResult.Applied
                            storage.reconcile(recovered) shouldBe CivInventoryApplyResult.Applied
                            reopened.completeInventoryTransaction(recovered).join() shouldBe recovered.after
                            reopened.loadInventoryTransactions().join() shouldBe emptyList()
                            reopened.close()
                            storage.unlock(id)
                            city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
                            city.mineProgress shouldBe mapOf(key to legacyStep)
                            city.workers.single().completedCycles shouldBe 1L
                            city.inventory!!.buildingStock(mine.id) shouldBe emptyMap()
                            CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE").toLong() shouldBe
                                CivsLogistics.QUARRY_SUPPLIES.getValue("COBBLESTONE") + first.outputs.getOrDefault("COBBLESTONE", 0)
                            oldPaid.forEach { (p, expected) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe expected }
                            runtime = CivsWorksites(plugin, access, { current, building -> storage.buildingAvailable(current, building) }, { _, _ -> false },
                                nanoTime = { 0L })
                            val next = nextWork()
                            withClue("cutover $legacyStep cursor=${city.minePlans}, first=$first, next=$next") {
                                // The same excavated cell now receives its paid ladder; it is not mined twice.
                                next.changes.single().position shouldBe first.changes.single().position
                                next.changes.single().before shouldBe "minecraft:air"
                                next.changes.single().after.startsWith("minecraft:ladder[") shouldBe true
                                next.inputs shouldBe mapOf("LADDER" to 1L)
                                next.outputs shouldBe emptyMap()
                                (requireNotNull(next.miningStep) >= requireNotNull(first.miningStep)) shouldBe true
                            }
                            city.workers.single().completedCycles shouldBe 1L
                            world.getBlockAt(first.changes.single().position.x, first.changes.single().position.y, first.changes.single().position.z).type shouldBe Material.AIR
                        }
                    }
                } finally {
                    tasks.close()
                    directory.toFile().deleteRecursively()
                }
            }
        }
    }
})
