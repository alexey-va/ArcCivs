package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsMineV7MigrationTest : FunSpec({
    test("V7 appends one independent lower floor without changing any V6 ordinal") {
        for (style in listOf("medievaloak", "nordic", "caledonia")) for (level in 1..3) {
            val mine = spatialBuilding(style, "miner", level, CivPosition(0, 70, 0))
            val old = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
            plan.version shouldBe 7
            plan.steps.take(old.steps.size) shouldBe old.steps
            val added = plan.steps.drop(old.steps.size)
            added.size shouldBe 1599
            added.filter { it.after == null }.map { it.point }.distinct().size shouldBe 990
            added.count { it.after?.startsWith("minecraft:ladder[") == true } shouldBe 4
            if (style == "caledonia" && level == 1) {
                println("V7 finite continuation: addedSteps=${added.size}, " +
                    "nativeCuts=${added.filter { it.after == null }.map { it.point }.distinct().size}, " +
                    "newRungs=${added.count { it.after?.startsWith("minecraft:ladder[") == true }}, " +
                    "placements=${added.mapNotNull { it.after }.groupingBy { it.substringBefore('[') }.eachCount()}")
            }
            plan.floors.dropLast(1) shouldBe old.floors
            plan.lowest shouldBe old.lowest - 4
            val spine = mapOf("spine" to plan.sections.getValue("spine").size.toLong())
            val lower = plan.floors.last().index
            plan.sectionReady("$lower/shaft/landing", 0, spine) shouldBe true
            val landing = spine + ("$lower/shaft/landing" to plan.sections.getValue("$lower/shaft/landing").size.toLong())
            plan.sectionReady("$lower/gallery/main", 0, landing) shouldBe true
            plan.sectionCount("${old.floors.last().index}/gallery/main", 0, landing) shouldBe 0L
            val beforeGenerators = plan.sections.filterKeys { !it.endsWith("/generator") }.mapValues { it.value.size.toLong() }
            val oldGenerator = "${old.floors.last().index}/gallery/generator"
            val generator = "$lower/gallery/generator"
            plan.sectionReady(oldGenerator, 0, beforeGenerators) shouldBe true
            plan.sectionReady(generator, 0, beforeGenerators) shouldBe false
            plan.sectionReady(generator, 0, beforeGenerators + (oldGenerator to 1L)) shouldBe true
        }
    }

    test("a real continuation receipt freezes exact sparse V6 authority and cannot pay an unfinished V6 branch") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(-1844, 70, -2696), 180)
        val old = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val before = sparseV6Proof(mine)
        val count = CivsMineProgress.retainedSpine(mine, before).getValue("spine").toInt()
        count shouldBe old.sections.getValue("spine").size
        val ordinal = plan.sections.getValue("spine")[count + 1]
        (ordinal > old.steps.lastIndex) shouldBe true
        val work = v7SpineReceipt(mine, ordinal)
        CivsMineProgress.initialSpineAccess(mine, before, work) shouldBe listOf(plan.sections.getValue("spine")[count])
        val after = CivsMineProgress.after(mine, before, null, work)
        after.version shouldBe 7
        CivsMineProgress.v6State(after) shouldBe before
        after.v5Proof shouldBe before.v5Proof
        after.v6Proof shouldBe CivMineV6Proof(before.nextStep, before.sectionSteps, before.excavation, before.seals)
        plan.sectionCount("6/gallery/main", after.nextStep, after.sectionSteps.orEmpty()) shouldBe 0L
        plan.sectionCount("7/shaft/landing", after.nextStep, after.sectionSteps.orEmpty()) shouldBe 0L
        old.completed(old.sections.getValue("6/gallery/main")[33], before.nextStep, before.sectionSteps.orEmpty()) shouldBe false
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, before, null, work.copy(
            miningSections = work.miningSections!! + ("6/gallery/main" to 200L))) }
        CivsMineProgress.valid(plan, after.copy(v6Proof = after.v6Proof!!.copy(
            sectionSteps = mapOf("foreign" to 1))), mine) shouldBe false
        val standing = old.floors.last().cells.map { it.copy(y = old.lowest) }.first {
            old.builtStanding(it, before.nextStep, before.sectionSteps.orEmpty())
        }
        CivsMineProgress.priorStanding(mine, after, CivsWorksiteLayouts.point(mine, standing)) shouldBe true
        val next = v7SpineReceipt(mine, plan.sections.getValue("spine")[count + 3], after.sectionSteps.orEmpty(), after.nextStep)
        CivsMineProgress.after(mine, after, null, next).v6Proof shouldBe after.v6Proof
        shouldThrow<IllegalArgumentException> { CivsMineProgress.after(mine, after, null, work.copy(miningPlanVersion = 6)) }
    }

    test("V6 authority detaches through the store codec and survives a later level witness") {
        val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 70, 0))
        val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
        val before = sparseV6Proof(mine)
        val ordinal = plan.sections.getValue("spine")[CivsMineProgress.retainedSpine(mine, before).getValue("spine").toInt() + 1]
        val after = CivsMineProgress.after(mine, before, null, v7SpineReceipt(mine, ordinal))
        val mutableSections = after.v6Proof!!.sectionSteps!!.toMutableMap()
        val mutableSeals = after.v6Proof!!.seals.orEmpty().toMutableMap()
        val mutable = after.copy(v6Proof = after.v6Proof!!.copy(sectionSteps = mutableSections, seals = mutableSeals))
        val city = spatialCity(UUID.randomUUID(), listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
            minePlans = mapOf(mine.id.toString() to mutable))
        val expected = city.copy(minePlans = mapOf(mine.id.toString() to CivsMineProgress.detached(mutable)))
        val pending = ArrayDeque<Runnable>()
        val root = Files.createTempDirectory("arc-civs-v6-proof-")
        try {
            CivsStore(root, Executor { pending.addLast(it) }).use { store ->
                val saving = store.save(city)
                mutableSections["foreign"] = 1
                mutableSeals["foreign"] = "minecraft:diamond_block"
                pending.removeFirst().run()
                saving.join() shouldBe expected
            }
            CivsStore(root, Executor(Runnable::run)).use { it.load().join() shouldBe listOf(expected) }
            val upgradedMine = mine.copy(level = 2, schematicId = mine.schematicId.dropLast(1) + "2")
            val upgraded = requireNotNull(CivsMineProgress.upgraded(mine, upgradedMine,
                expected.minePlans!!.getValue(mine.id.toString()), null))
            upgraded.levels!!.single().state() shouldBe expected.minePlans!!.getValue(mine.id.toString())
            CivsMineProgress.validLevels(upgradedMine, upgraded) shouldBe true
        } finally { root.toFile().deleteRecursively() }
    }

    for (alreadyApplied in listOf(false, true))
    test("a pending V6 receipt replays its frozen ordinal once under V7 with applied cell $alreadyApplied") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("frozen-v6-$alreadyApplied").also(paper.server::addWorld)
            val plugin = paper.createSimplePlugin("FrozenV6Receipt")
            val mine = spatialBuilding("caledonia", "miner", 1, CivPosition(0, 63, 0))
            val worker = spatialWorker(mine, "IRON_PICKAXE")
            val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
            val before = CivMinePlanState(6, 0, sectionSteps = emptyMap())
            val city = spatialCity(world.uid, listOf(mine), worker).copy(minePlans = mapOf(mine.id.toString() to before))
            val ordinal = plan.sections.getValue("spine").first()
            val sections = plan.advanceSection(ordinal, emptyMap())
            val point = CivsWorksiteLayouts.point(mine, plan.steps[ordinal].point)
            val work = CivWorldWork(worker.id, worker.completedCycles, mine.id, mine.entrance,
                listOf(CivPlanBlock(point, "minecraft:air", plan.expectedAfter(ordinal, mine.rotation))),
                inputs = mapOf("COBBLESTONE" to 1L), status = "digging-mine", focus = point,
                miningPlanVersion = 6, miningStep = plan.normalizedProgress(0, sections).first,
                miningAccessStep = 0, miningSections = sections, miningAccessSections = emptyMap(), miningOrdinal = ordinal)
            val after = CivsRules.finishWorldWork(city, work, 1_000)
            val transaction = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                worldChanges = work.changes, mine = CivMineReceipt(mine, before, null, work)).validated()
            val root = Files.createTempDirectory("arc-civs-v6-receipt-")
            try {
                world.loadChunk(point.x shr 4, point.z shr 4)
                world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(
                    if (alreadyApplied) work.changes.single().after else work.changes.single().before)
                CivsStore(root, Executor(Runnable::run)).use { store ->
                    store.saveInventoryTransaction(transaction).join()
                    val restored = store.loadInventoryTransactions().join().single().validated()
                    LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                        CivsWorld(plugin, tasks) { listOf(city) }.use { access ->
                            val runtime = CivsWorksites(plugin, access, { _, _ -> emptyMap() }, { _, _ -> false })
                            CivsStorage(plugin) { emptyArray() }.use { storage ->
                                storage.lock(restored)
                                storage.validateMineNative = { tx ->
                                    val receipt = requireNotNull(tx.mine)
                                    runtime.validateMineReceiptNative(receipt.before(tx.after), receipt.work)
                                    null
                                }
                                repeat(2) { storage.reconcile(restored) shouldBe CivInventoryApplyResult.Applied }
                                world.getBlockAt(point.x, point.y, point.z).blockData.asString shouldBe work.changes.single().after
                                restored.after.minePlans!!.getValue(mine.id.toString()).version shouldBe 6
                                restored.after.workers.single().completedCycles shouldBe worker.completedCycles + 1
                                CivsEquipment.plainCount(restored.after.workers.single(), "COBBLESTONE") shouldBe 31
                                restored.after.stock shouldBe city.stock
                            }
                        }
                    }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }
})

private fun sparseV6Proof(mine: CivBuilding): CivMinePlanState {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine, 6))
    val sections = mapOf("spine" to plan.sections.getValue("spine").size.toLong(),
        "6/shaft/landing" to plan.sections.getValue("6/shaft/landing").size.toLong(), "6/gallery/main" to 33L)
    val normalized = plan.normalizedProgress(0, sections)
    return CivMinePlanState(6, normalized.first, sectionSteps = normalized.second,
        excavation = plan.steps[plan.sections.getValue("6/gallery/main")[30]].point,
        v5Proof = CivMineV5Proof(0, emptyMap()))
}

private fun v7SpineReceipt(mine: CivBuilding, ordinal: Int, before: Map<String, Long> = emptyMap(), prefix: Long = 0): CivWorldWork {
    val plan = requireNotNull(CivsMinePlan.forBuilding(mine))
    val offset = plan.sections.getValue("spine").indexOf(ordinal)
    val access = before + ("spine" to offset.toLong())
    val sections = plan.advanceSection(ordinal, access)
    return CivWorldWork(UUID.randomUUID(), 0, mine.id, mine.entrance,
        listOf(CivPlanBlock(CivsWorksiteLayouts.point(mine, plan.steps[ordinal].point), "minecraft:air", plan.expectedAfter(ordinal, mine.rotation))),
        status = "digging-mine", miningPlanVersion = 7, miningStep = plan.normalizedProgress(prefix, sections).first,
        miningAccessStep = plan.normalizedProgress(prefix, access).first, miningSections = sections,
        miningAccessSections = access, miningOrdinal = ordinal)
}
