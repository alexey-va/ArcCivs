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
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineAutoFallbackTest : FunSpec({
    test("only active native Auto miners with a pickaxe qualify for fallback") {
        mineFallbackFixture(automatic = true) { _, _, _, city, mine, worker, _ ->
            CivsMineFallback.eligible(city, mine, worker) shouldBe true
            CivsMineFallback.eligible(city.copy(autonomy = null), mine, worker) shouldBe false
            CivsMineFallback.eligible(city.copy(inventory = null), mine, worker) shouldBe false
            CivsMineFallback.eligible(city.copy(autonomy = CivAutonomy(paused = true)), mine, worker) shouldBe false
            CivsMineFallback.eligible(city, mine, worker.copy(items = worker.items!!.toMutableList().also { it[4] = null })) shouldBe false
        }
    }

    test("native Auto keeps working at its own safe storage when a real liquid branch is blocked") {
        mineFallbackFixture(automatic = true) { paper, world, runtime, city, mine, worker, target ->
            repeat(32) { runtime.pulse(listOf(city)); paper.performTicks(5) }
            val work = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            work.status shouldBe "digging-mine"
            work.durationMillis shouldBe 6_000L
            work.changes shouldBe emptyList()
            work.inputs shouldBe emptyMap()
            work.outputs.values.single() shouldBe 1L
            (work.outputs.keys.single() in CivsMineFallback.OUTPUTS) shouldBe true
            work.miningStep shouldBe null
            work.miningPlanVersion shouldBe null
            work.miningSections shouldBe null
            work.miningGenerator shouldBe false
            work.autonomousVisual shouldBe false
            citizenWorkFocus(work) shouldBe null
            runtime.validate(city, work)
            for (material in listOf("RAW_GOLD", "REDSTONE", "DIAMOND")) {
                val selected = work.copy(outputs = mapOf(material to 1L))
                CivsMineFallback.matches(selected) shouldBe true
                val after = CivsRules.finishWorldWork(city, selected, 10_000)
                CivsEquipment.plainCount(after.workers.single(), material) shouldBe
                    CivsEquipment.plainCount(worker, material) + 1
                val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList())
                Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated().after shouldBe after
                shouldThrow<CivsRejection> { CivsRules.finishWorldWork(after, selected, 10_000) }.reason shouldBe "stale-delivery"
            }
            world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.WATER
            val completed = CivsRules.finishWorldWork(city, work, 10_000)
            completed.minePlans shouldBe city.minePlans
            completed.stock shouldBe city.stock
            completed.workers.single().completedCycles shouldBe worker.completedCycles + 1
            val material = work.outputs.keys.single()
            CivsEquipment.plainCount(completed.workers.single(), material) shouldBe CivsEquipment.plainCount(worker, material) + 1
            shouldThrow<CivsRejection> { CivsRules.finishWorldWork(completed, work, 10_000) }.reason shouldBe "stale-delivery"
            val tx = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, completed, emptyList())
            Gson().fromJson(Gson().toJson(tx), CivInventoryTransaction::class.java).validated().after shouldBe completed
            val directory = Files.createTempDirectory("arc-civs-mine-fallback-receipt-")
            try {
                CivsStore(directory, Executor(Runnable::run)).use { store -> store.saveInventoryTransaction(tx).join() shouldBe tx }
                CivsStore(directory, Executor(Runnable::run)).use { restarted ->
                    val accepted = restarted.loadInventoryTransactions().join().single()
                    accepted.after shouldBe completed
                    shouldThrow<CivsRejection> { CivsRules.finishWorldWork(accepted.after, work, 10_000) }.reason shouldBe "stale-delivery"
                    restarted.deleteInventoryTransaction(tx.id).join() shouldBe true
                }
            } finally { directory.toFile().deleteRecursively() }
            val cycle = CitizenCycle(CitizenToken(mine.id, worker.completedCycles, 1, null), work.durationMillis,
                completeAtWorkplace = true)
            cycle.start()
            cycle.arrive(0)
            cycle.arrive(0)
            for (at in 1_000L..5_000L step 1_000) cycle.work(at)
            cycle.phase shouldBe CitizenPhase.WORKING
            cycle.work(6_000)
            cycle.phase shouldBe CitizenPhase.READY
        }
    }

    test("ordinary mines keep the real liquid refusal") {
        mineFallbackFixture(automatic = false) { paper, world, runtime, city, _, worker, target ->
            repeat(256) { runtime.pulse(listOf(city)); paper.performTicks(5) }
            runtime.plan(city, worker) shouldBe null
            runtime.idleReason(city, worker) shouldBe "worksite-liquid"
            world.getBlockAt(target.x, target.y, target.z).type shouldBe Material.WATER
            city.minePlans shouldBe null
            worker.completedCycles shouldBe 0
        }
    }

    test("a finite native lava visibility sweep yields to Auto production without losing its frontier") {
        mineFallbackFixture(automatic = true, hazard = Material.LAVA, settings = CivsWorksiteSettings(rayChecksPerPulse = 1)) { paper, _, runtime, city, _, worker, _ ->
            var work: CivWorldWork? = null
            for (admission in 1..CivsMineFallback.DISCOVERY_ADMISSIONS) {
                runtime.pulse(listOf(city))
                work = runtime.plan(city, worker)
                if (work != null) break
                paper.performTicks(5)
            }
            CivsMineFallback.matches(requireNotNull(work) { runtime.debug().toString() }) shouldBe true
            val entry = (runtime.debug()["entries"] as List<*>).single() as Map<*, *>
            val frontier = requireNotNull(entry["mineSafety"])
            val sections = requireNotNull(entry["mineSections"])
            val after = CivsRules.finishWorldWork(city, work, 10_000)
            runtime.invalidate(worker.id, work)
            val retained = (runtime.debug()["entries"] as List<*>).single() as Map<*, *>
            retained["mineSafety"] shouldBe frontier
            retained["mineSections"] shouldBe sections
            after.minePlans shouldBe city.minePlans
            after.workers.single().completedCycles shouldBe worker.completedCycles + 1
        }
    }

    test("fallback revalidates native loaded storage locks and Lands before committing items") {
        var locked = false
        mineFallbackFixture(automatic = true, locked = { locked }) { paper, world, runtime, city, mine, worker, _ ->
            repeat(32) { runtime.pulse(listOf(city)); paper.performTicks(5) }
            val work = requireNotNull(runtime.plan(city, worker))
            val standing = work.standing
            world.getBlockAt(standing.x, standing.y, standing.z).type = Material.STONE
            shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
            world.getBlockAt(standing.x, standing.y, standing.z).type = Material.AIR
            locked = true
            shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
            locked = false
            world.unloadChunk(standing.x shr 4, standing.z shr 4)
            shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
            world.loadChunk(standing.x shr 4, standing.z shr 4)
            val lands = paper.createSimplePlugin("Lands")
            paper.server.pluginManager.disablePlugin(lands)
            shouldThrow<CivsRejection> { runtime.validate(city, work) }.reason shouldBe "stale-worksite"
            worker.completedCycles shouldBe 0
            city.minePlans shouldBe null
            world.getBlockAt(mine.control!!.x, mine.control.y, mine.control.z).type shouldBe Material.BARREL
        }
    }
})

private fun mineFallbackFixture(automatic: Boolean, locked: (CivPosition) -> Boolean = { false }, hazard: Material = Material.WATER, settings: CivsWorksiteSettings = CivsWorksiteSettings(),
    body: (MockBukkitTestRuntime, CivsWorksiteWorldFixture, CivsWorksites, Settlement, CivBuilding, CivWorker, CivPosition) -> Unit) = failOnUnsupportedMockBukkitOperation {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("mine-auto-fallback-$automatic").also(paper.server::addWorld)
        val plugin = paper.createSimplePlugin("MineAutoFallback")
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 63, 0))
        val worker = spatialWorker(mine, "IRON_PICKAXE")
        val city = spatialCity(world.uid, listOf(mine), worker).copy(autonomy = if (automatic) CivAutonomy() else null,
            inventory = CivInventory(if (automatic) CivStorageMode.PHYSICAL else CivStorageMode.VIRTUAL))
        for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val bottom = CivsWorksiteLayouts.point(mine, plan.bottom)
        for (y in bottom.y..65) world.getBlockAt(bottom.x, y, bottom.z).blockData =
            Bukkit.createBlockData("minecraft:ladder[facing=${plan.facing},waterlogged=false]")
        val target = bottom.copy(y = bottom.y - 1, z = bottom.z + 1)
        world.getBlockAt(target.x, target.y, target.z).type = hazard
        val control = requireNotNull(mine.control)
        for (dx in -1..1) for (dz in -1..1) {
            world.getBlockAt(control.x + dx, control.y - 1, control.z + dz).type = Material.STONE
            world.getBlockAt(control.x + dx, control.y, control.z + dz).type = Material.AIR
            world.getBlockAt(control.x + dx, control.y + 1, control.z + dz).type = Material.AIR
        }
        world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
        LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
            CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, point -> locked(point) }, nanoTime = { 0L }, settings = settings)
                body(paper, world, runtime, city, mine, worker, target)
            }
        }
    }
}
