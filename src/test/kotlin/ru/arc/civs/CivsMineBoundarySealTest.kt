package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.structure.StructureRotation
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineBoundarySealTest : FunSpec({
    test("the rotated step215 cuts its occluding native cell then repairs the hidden AIR boundary after save and restart") {
        withMineBoundary { world, mine, original, access, plugin ->
            var city = original
            var runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
            val worker = city.workers.single()
            val cut = nextBoundaryWork(runtime, city, world)
            cut.status shouldBe "digging-mine"
            cut.changes.single().position shouldBe BOUNDARY_CUT
            cut.changes.single().before shouldBe "minecraft:stone"
            cut.changes.single().after shouldBe "minecraft:air"
            cut.inputs shouldBe emptyMap()
            cut.outputs shouldBe mapOf("COBBLESTONE" to 1L)
            cut.miningStep shouldBe 216L
            assertBoundaryStanding(world, mine, cut)
            citizenCanReachBlock(world, Location(world, cut.standing.x + .5, cut.standing.y + 1.62,
                cut.standing.z + .5), BOUNDARY_WALL, CITIZEN_MINE_REACH) shouldBe false
            runtime.validate(city, cut)
            // A deferred AIR seal is not permission to admit liquid appearing during the action.
            world.getBlockAt(BOUNDARY_WALL.x, BOUNDARY_WALL.y, BOUNDARY_WALL.z).type = Material.WATER
            shouldThrow<CivsRejection> { runtime.validate(city, cut) }.reason shouldBe "stale-worksite"
            world.getBlockAt(BOUNDARY_CUT.x, BOUNDARY_CUT.y, BOUNDARY_CUT.z).type shouldBe Material.STONE
            city shouldBe original
            world.getBlockAt(BOUNDARY_WALL.x, BOUNDARY_WALL.y, BOUNDARY_WALL.z).type = Material.AIR

            val directory = Files.createTempDirectory("mine-boundary-step215-")
            try {
                CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                CivsStorage(plugin) { emptyArray() }.use { storage ->
                    fun commit(work: CivWorldWork) {
                        runtime.validate(city, work)
                        val currentWorker = city.workers.single()
                        val id = UUID.randomUUID()
                        storage.reserve(city, id)
                        val native = storage.prepareWork(city, mine.id, work.storageInputs(currentWorker), emptyMap(), work.changes)
                        val after = CivsRules.finishWorldWork(city, work, 100_000 + work.cycle * 5_000)
                            .copy(inventory = native.inventoryAfter)
                        val receipt = CivInventoryTransaction(id, city.id, city.revision, after, native.changes,
                            worldChanges = native.worldChanges).validated()
                        storage.seal(receipt, keepViewersOpen = true)
                        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
                        storage.unlock(id)
                        city = receipt.after
                        CivsStore(directory, Executor { it.run() }).use { it.save(city).join() }
                        runtime.invalidate(work.workerId)
                    }
                    commit(cut)
                    city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe 216L
                    CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe
                        CivsEquipment.plainCount(worker, "COBBLESTONE") + 1

                    city = CivsStore(directory, Executor { it.run() }).use { it.load().join().single() }
                    runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                    val seal = nextBoundaryWork(runtime, city, world)
                    seal.status shouldBe "securing-mine"
                    seal.changes.single().position shouldBe BOUNDARY_WALL
                    seal.changes.single().before shouldBe "minecraft:air"
                    seal.changes.single().after shouldBe "minecraft:cobblestone"
                    seal.inputs shouldBe mapOf("COBBLESTONE" to 1L)
                    seal.outputs shouldBe emptyMap()
                    seal.miningStep shouldBe 216L
                    assertBoundaryStanding(world, mine, seal)
                    world.getBlockAt(BOUNDARY_CUT.x, BOUNDARY_CUT.y - 1, BOUNDARY_CUT.z).type shouldBe Material.STONE
                    commit(seal)
                    city.minePlans!!.getValue(mine.id.toString()) shouldBe
                        CivMinePlanState(CivsMinePlan.VERSION, 216, legacyStep = 214, v3Step = 83)
                    CivsEquipment.plainCount(city.workers.single(), "COBBLESTONE") shouldBe
                        CivsEquipment.plainCount(worker, "COBBLESTONE")
                    world.getBlockAt(BOUNDARY_WALL.x, BOUNDARY_WALL.y, BOUNDARY_WALL.z).type shouldBe Material.COBBLESTONE
                    val lowerCut = nextBoundaryWork(runtime, city, world)
                    lowerCut.status shouldBe "digging-mine"
                    lowerCut.changes.single().position shouldBe BOUNDARY_CUT.copy(y = BOUNDARY_CUT.y - 1)
                    lowerCut.changes.single().after shouldBe "minecraft:air"
                    runtime.validate(city, lowerCut)
                    shouldThrow<CivsRejection> { CivsRules.finishWorldWork(city, seal, 2_000_000) }.reason shouldBe "stale-delivery"
                }
            } finally { directory.toFile().deleteRecursively() }
        }
    }

    test("missing carried cobblestone never opens the step215 native cell beside the hidden AIR boundary") {
        withMineBoundary { world, mine, original, access, plugin ->
            val worker = original.workers.single().let { worker -> worker.copy(items =
                CivsEquipment.slots(worker).map { it?.takeUnless { item -> item.material == "COBBLESTONE" } }) }
            val city = CivsRules.validate(original.copy(workers = listOf(worker)))
            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
            repeat(128) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            runtime.idleReason(city, worker) shouldBe "waiting-cobblestone"
            world.getBlockAt(BOUNDARY_CUT.x, BOUNDARY_CUT.y, BOUNDARY_CUT.z).type shouldBe Material.STONE
            world.getBlockAt(BOUNDARY_WALL.x, BOUNDARY_WALL.y, BOUNDARY_WALL.z).type shouldBe Material.AIR
            city.minePlans!!.getValue(mine.id.toString()).nextStep shouldBe 215L
            city.workers.single() shouldBe worker
        }
    }
})

private val BOUNDARY_CUT = CivPosition(-89, 62, -40)
private val BOUNDARY_WALL = CivPosition(-88, 62, -40)

private fun nextBoundaryWork(runtime: CivsWorksites, city: Settlement, world: CivsWorksiteWorldFixture): CivWorldWork {
    repeat(256) {
        val rays = world.rayTraces
        runtime.pulse(listOf(city))
        (world.rayTraces - rays <= 8) shouldBe true
        runtime.plan(city, city.workers.single())?.let { return it }
    }
    return withClue("step215 boundary idle=${runtime.idleReason(city, city.workers.single())}") {
        requireNotNull(runtime.plan(city, city.workers.single()))
    }
}

private fun assertBoundaryStanding(world: CivsWorksiteWorldFixture, mine: CivBuilding, work: CivWorldWork) {
    val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
    geometry.builtStanding(CivsMinePlan.localPoint(mine, work.standing), requireNotNull(work.miningAccessStep)) shouldBe true
    citizenStandable(world, work.standing) shouldBe true
    citizenPacketBodyClear(world, CitizenPoint(work.standing.x + .5, work.standing.y.toDouble(), work.standing.z + .5), false) shouldBe true
    citizenCanReachBlock(world, Location(world, work.standing.x + .5, work.standing.y + 1.62,
        work.standing.z + .5), requireNotNull(work.focus), CITIZEN_MINE_REACH) shouldBe true
}

private fun withMineBoundary(action: (CivsWorksiteWorldFixture, CivBuilding, Settlement, CivsWorld, Plugin) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("mine-boundary-step215").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("MineBoundaryStep215")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            try {
                val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
                val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
                CivsWorksiteLayouts.point(mine, geometry.steps[215].point) shouldBe BOUNDARY_CUT
                val worker = spatialWorker(mine, "IRON_PICKAXE").copy(completedCycles = 245)
                val key = mine.id.toString()
                val city = CivsRules.validate(spatialCity(world.uid, listOf(mine), worker).copy(
                    center = CivPosition(-25, 64, -20), mineProgress = mapOf(key to 214L),
                    minePlans = mapOf(key to CivMinePlanState(CivsMinePlan.VERSION, 215, legacyStep = 214, v3Step = 83))))
                for (x in -7..-5) for (z in -4..-2) world.loadChunk(x, z)
                for (step in geometry.steps.take(215)) {
                    val point = CivsWorksiteLayouts.point(mine, step.point)
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(step.after ?: "minecraft:air").also {
                        if (step.after != null) it.rotate(StructureRotation.COUNTERCLOCKWISE_90)
                    }
                }
                val top = CivsWorksiteLayouts.point(mine, geometry.bottom)
                // Hash-pinned miner1 authored rungs: local -5..-3; air -2..-1; dirt cap 0.
                for (dy in 0..5) {
                    world.getBlockAt(top.x, top.y + dy, top.z).blockData = Bukkit.createBlockData(when {
                        dy <= 2 -> "minecraft:ladder[facing=south,waterlogged=false]"
                        dy < 5 -> "minecraft:air"
                        else -> "minecraft:dirt"
                    })
                    if (dy < 5) {
                        val backing = CivsWorksiteLayouts.point(mine, geometry.backing)
                        world.getBlockAt(backing.x, top.y + dy, backing.z).type = Material.COBBLESTONE
                    }
                }
                // Minimal observed boundary, not a reconstruction of the whole saved world.
                // Cycles243/244 already paid these lower seals; native probe confirmed the upper cut is STONE.
                world.getBlockAt(-89, 61, -39).type = Material.COBBLESTONE
                world.getBlockAt(-88, 61, -40).type = Material.COBBLESTONE
                world.getBlockAt(BOUNDARY_CUT.x, BOUNDARY_CUT.y, BOUNDARY_CUT.z).type = Material.STONE
                world.getBlockAt(BOUNDARY_WALL.x, BOUNDARY_WALL.y, BOUNDARY_WALL.z).type = Material.AIR
                CivsWorld(plugin, tasks) { listOf(city) }.use { access -> action(world, mine, city, access, plugin) }
            } finally { tasks.close() }
        }
    }
}
